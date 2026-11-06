package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.describe.ImageDescribeClient;
import cn.aigc.dit.generate.describe.ImageDescribePrompts;
import cn.aigc.dit.generate.describe.ImageDescribeResult;
import cn.aigc.dit.generate.seedream.SeedreamCharacterBinds;
import cn.aigc.dit.generate.seedream.SeedreamGenerateProvider;
import cn.aigc.dit.generate.seedream.SeedreamOriginUrls;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaAssetGeneration;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 官方 Seedance 出片前，把写实角色参考换成原图旁边的 Seedream 定妆图。
 * 不新建资产、不插入生成记录，也不改角色页当前选用图。
 */
@Slf4j
@Service
public class SeedreamShotBindService {

    private final DramaSeriesMapper seriesMapper;
    private final DramaAssetMapper assetMapper;
    private final DramaAssetGenerationMapper generationMapper;
    private final GenTaskMapper genTaskMapper;
    private final DramaAssetService dramaAssetService;
    private final ObjectStorage objectStorage;
    private final ObjectMapper objectMapper;
    private final ImageDescribeClient imageDescribeClient;
    private final SeedreamGenerateProvider seedream;
    private final ConcurrentHashMap<String, Object> imageLocks = new ConcurrentHashMap<>();

    public SeedreamShotBindService(DramaSeriesMapper seriesMapper,
                                   DramaAssetMapper assetMapper,
                                   DramaAssetGenerationMapper generationMapper,
                                   GenTaskMapper genTaskMapper,
                                   DramaAssetService dramaAssetService,
                                   ObjectStorage objectStorage,
                                   ObjectMapper objectMapper,
                                   ImageDescribeClient imageDescribeClient,
                                   SeedreamGenerateProvider seedream) {
        this.seriesMapper = seriesMapper;
        this.assetMapper = assetMapper;
        this.generationMapper = generationMapper;
        this.genTaskMapper = genTaskMapper;
        this.dramaAssetService = dramaAssetService;
        this.objectStorage = objectStorage;
        this.objectMapper = objectMapper;
        this.imageDescribeClient = imageDescribeClient;
        this.seedream = seedream;
    }

    /**
     * 满足条件时改写本镜角色参考 key。转图失败抛出异常，调用方不得把原图送去出片。
     */
    public void prepare(GenTask task, AiModel videoModel) {
        if (task == null || task.getParams() == null) {
            return;
        }
        Map<String, Object> params = task.getParams();
        if (!GenerateRequests.isDramaShot(params) || !ModelCategory.VIDEO.equalsIgnoreCase(task.getCategory())) {
            return;
        }
        if (framesMode(params) || !officialSeedance(params, videoModel)) {
            return;
        }
        Long seriesId = longVal(params.get("seriesId"));
        DramaSeries series = seriesId == null ? null : seriesMapper.selectById(seriesId);
        if (series == null || !DramaStyles.isRealistic(series.getStyleKey())) {
            return;
        }
        Set<String> characterKeys = characterImageKeys(task.getUserId(), longVal(params.get("episodeId")));
        List<String> targets = characterImageRefs(params.get("references"), characterKeys);
        if (targets.isEmpty() || !seedreamEnabled()) {
            return;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : targets) {
            resolved.computeIfAbsent(key, ignored -> ensure(key, series, task.getUserId()));
        }
        if (!rewriteReferences(task, resolved)) {
            return;
        }
        genTaskMapper.updateById(task);
        log.info("seedream sheet refs ready taskId={} keys={}", task.getId(), resolved);
    }

    private String ensure(String originalKey, DramaSeries series, Long userId) {
        synchronized (lockFor(originalKey)) {
            Instant now = Instant.now();
            SeedreamCharacterBinds.Bind bind = SeedreamCharacterBinds.read(objectStorage, objectMapper, originalKey);
            if (bind != null && SeedreamCharacterBinds.fresh(bind, now) && objectStorage.exists(bind.imageKey())) {
                log.info("seedream sheet reuse key={} image={}", originalKey, bind.imageKey());
                return bind.imageKey();
            }
            if (bind == null && nativeSeedreamFresh(originalKey, now)) {
                log.info("seedream sheet keep native key={}", originalKey);
                return originalKey;
            }
            SeedreamCharacterBinds.deleteAll(objectStorage, originalKey);
            return createSheet(originalKey, series, userId);
        }
    }

    /**
     * 角色全身图生文后纯文生图。风格句写在正文后面，不用「原创CGI数字人」。
     */
    private String createSheet(String originalKey, DramaSeries series, Long userId) {
        byte[] imageBytes = objectStorage.getBytes(originalKey);
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalStateException("角色参考图不存在或为空");
        }
        DramaAssetService.ConvertArkCredential credential = dramaAssetService.resolveConvertArkCredential(series);
        ImageDescribeResult described;
        try {
            described = imageDescribeClient.describe(
                    imageBytes,
                    DramaAssetService.mimeFromStorageKey(originalKey),
                    ImageDescribePrompts.CHARACTER_REALISM,
                    credential.endpoint(),
                    credential.apiKey(),
                    null);
        } catch (RuntimeException e) {
            throw sheetFailed(e);
        }
        String prompt = described == null ? "" : described.realisticPrompt();
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalStateException("图生文未得到可用提示词");
        }
        prompt = DramaAssetService.trimPrompt(prompt);
        String modelKey = dramaAssetService.configuredSeedreamConvertModelKey();
        if (!StringUtils.hasText(modelKey)) {
            modelKey = AdapterCatalog.SEEDREAM_5_0_MODEL;
        }
        String ratio = StringUtils.hasText(series.getAspectRatio())
                ? series.getAspectRatio().trim()
                : DramaContract.DEFAULT_ASPECT_RATIO;
        GenerateRequest request = new GenerateRequest()
                .setUserId(userId)
                .setCategory(ModelCategory.IMAGE)
                .setMode(GenerateMode.T2I)
                .setAdapterKey(AdapterCatalog.SEEDREAM_5_0)
                .setModelKey(modelKey.trim())
                .setPrompt(prompt)
                .setRatio(ratio)
                .setResolution("2K")
                .setCopies(1)
                .setEndpoint(credential.endpoint())
                .setApiKey(credential.apiKey());
        String imageKey = SeedreamCharacterBinds.imageKey(originalKey);
        try {
            seedream.writeTextToImage(request, imageKey);
        } catch (RuntimeException e) {
            throw sheetFailed(e);
        }
        SeedreamCharacterBinds.save(objectStorage, objectMapper, originalKey, imageKey, Instant.now());
        log.info("seedream sheet created from={} image={}", originalKey, imageKey);
        return imageKey;
    }

    private static IllegalStateException sheetFailed(RuntimeException e) {
        String message = e.getMessage() == null ? "请稍后重试" : e.getMessage();
        return new IllegalStateException("角色定妆图生成失败：" + message, e);
    }

    private boolean nativeSeedreamFresh(String storageKey, Instant now) {
        Instant saved = SeedreamOriginUrls.savedAt(objectStorage, objectMapper, storageKey);
        return saved != null && now.isBefore(saved.plus(SeedreamCharacterBinds.BIND_TTL));
    }

    private boolean seedreamEnabled() {
        try {
            return dramaAssetService.resolveSeedreamConvertCatalogModel() != null;
        } catch (BizException e) {
            return false;
        }
    }

    private boolean rewriteReferences(GenTask task, Map<String, String> resolved) {
        Object raw = task.getParams().get("references");
        if (!(raw instanceof List<?> list)) {
            return false;
        }
        boolean changed = false;
        List<Object> next = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                next.add(item);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            Object keyObj = copy.get("storageKey");
            String key = keyObj == null ? null : String.valueOf(keyObj).trim();
            String replacement = key == null ? null : resolved.get(key);
            if (StringUtils.hasText(replacement) && !replacement.equals(key)) {
                copy.put("storageKey", replacement);
                changed = true;
            }
            next.add(copy);
        }
        if (!changed) {
            return false;
        }
        Map<String, Object> params = new LinkedHashMap<>(task.getParams());
        params.put("references", next);
        task.setParams(params);
        return true;
    }

    private Set<String> characterImageKeys(Long userId, Long episodeId) {
        if (episodeId == null) {
            return Set.of();
        }
        LambdaQueryWrapper<DramaAsset> query = new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episodeId)
                .eq(DramaAsset::getType, DramaContract.ASSET_TYPE_CHARACTER);
        if (userId != null) {
            query.eq(DramaAsset::getUserId, userId);
        }
        List<DramaAsset> assets = assetMapper.selectList(query);
        if (assets == null || assets.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        List<Long> ids = new ArrayList<>();
        for (DramaAsset asset : assets) {
            if (asset == null) {
                continue;
            }
            if (asset.getId() != null) {
                ids.add(asset.getId());
            }
            if (StringUtils.hasText(asset.getImageStorageKey())) {
                keys.add(asset.getImageStorageKey().trim());
            }
        }
        if (!ids.isEmpty()) {
            LambdaQueryWrapper<DramaAssetGeneration> generations = new LambdaQueryWrapper<DramaAssetGeneration>()
                    .in(DramaAssetGeneration::getAssetId, ids);
            if (userId != null) {
                generations.eq(DramaAssetGeneration::getUserId, userId);
            }
            List<DramaAssetGeneration> rows = generationMapper.selectList(generations);
            if (rows != null) {
                for (DramaAssetGeneration row : rows) {
                    if (row != null && StringUtils.hasText(row.getStorageKey())) {
                        keys.add(row.getStorageKey().trim());
                    }
                }
            }
        }
        return keys;
    }

    private static List<String> characterImageRefs(Object rawRefs, Set<String> characterKeys) {
        if (!(rawRefs instanceof List<?> list) || characterKeys.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String type = map.get("type") == null ? null : String.valueOf(map.get("type"));
            String key = map.get("storageKey") == null ? null : String.valueOf(map.get("storageKey")).trim();
            if (imageRef(type, key) && characterKeys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static boolean imageRef(String type, String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        if (AssetKind.VIDEO.equalsIgnoreCase(type) || AssetKind.AUDIO.equalsIgnoreCase(type)) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return !(lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")
                || lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a")
                || lower.endsWith(".aac"));
    }

    private static boolean framesMode(Map<String, Object> params) {
        return DramaVideoGenerateModes.isI2v(text(params, "videoGenerateMode"))
                || DramaVideoGenerateModes.isI2v(text(params, "mode"));
    }

    private static boolean officialSeedance(Map<String, Object> params, AiModel model) {
        String adapter = text(params, "adapterKey");
        if (!StringUtils.hasText(adapter) && model != null) {
            adapter = AdapterCatalog.resolveKey(
                    model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        }
        if (AdapterCatalog.SEEDANCE_2.equalsIgnoreCase(adapter) || AdapterCatalog.isRunningHub(adapter)) {
            return false;
        }
        if (AdapterCatalog.SEEDANCE_2_0.equalsIgnoreCase(adapter)
                || AdapterCatalog.SEEDANCE_2_5.equalsIgnoreCase(adapter)) {
            return true;
        }
        String modelKey = model == null ? null : model.getModelKey();
        return AdapterCatalog.looksLikeOfficialSeedance(modelKey);
    }

    private Object lockFor(String originalKey) {
        return imageLocks.computeIfAbsent(originalKey, ignored -> new Object());
    }

    private static String text(Map<String, Object> params, String key) {
        if (params == null || params.get(key) == null) {
            return null;
        }
        String value = String.valueOf(params.get(key)).trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    private static Long longVal(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
