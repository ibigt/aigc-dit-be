package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.ark.ArkKeys;
import cn.aigc.dit.generate.ark.ArkUrls;
import cn.aigc.dit.generate.describe.ImageDescribeClient;
import cn.aigc.dit.generate.describe.ImageDescribePrompts;
import cn.aigc.dit.generate.describe.ImageDescribeResult;
import cn.aigc.dit.generate.seedream.SeedreamOriginUrls;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaAssetGeneration;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.req.DramaAssetSaveReq;
import cn.aigc.dit.server.drama.model.req.DramaAssetVersionReq;
import cn.aigc.dit.generate.ark.ArkAssetRefs;
import cn.aigc.dit.server.drama.model.resp.DramaAssetGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetGenerationResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetReviewResp;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.AssetResp;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.service.AssetService;
import cn.aigc.dit.server.service.CreationService;
import cn.aigc.dit.server.service.GenerateService;
import cn.aigc.dit.server.service.MediaRetentionService;
import cn.aigc.dit.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class DramaAssetService {

    /** generation.source：上传图经图生文→Seedream 5.0 转成的可信资产图。 */
    public static final String SOURCE_ROUNDTRIP = "roundtrip";

    private final DramaAssetMapper assetMapper;
    private final DramaAssetGenerationMapper assetGenerationMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSeriesMapper seriesMapper;
    private final AiModelMapper aiModelMapper;
    private final GenTaskMapper genTaskMapper;
    private final ObjectProvider<DramaTextService> textService;
    private final GenerateService generateService;
    private final AssetService assetService;
    private final DramaStoryboardService storyboardService;
    private final ArkCharacterReview characterReview;
    private final CreationService creationService;
    private final MediaRetentionService mediaRetention;
    private final ObjectStorage objectStorage;
    private final AesGcmEncryptor encryptor;
    private final ImageDescribeClient imageDescribeClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public DramaAssetService(DramaAssetMapper assetMapper,
                             DramaAssetGenerationMapper assetGenerationMapper,
                             DramaEpisodeMapper episodeMapper,
                             DramaSeriesMapper seriesMapper,
                             AiModelMapper aiModelMapper,
                             GenTaskMapper genTaskMapper,
                             ObjectProvider<DramaTextService> textService,
                             GenerateService generateService,
                             AssetService assetService,
                             DramaStoryboardService storyboardService,
                             ArkCharacterReview characterReview,
                             CreationService creationService,
                             MediaRetentionService mediaRetention,
                             ObjectStorage objectStorage,
                             AesGcmEncryptor encryptor,
                             ImageDescribeClient imageDescribeClient,
                             PlatformTransactionManager transactionManager,
                             ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.assetGenerationMapper = assetGenerationMapper;
        this.episodeMapper = episodeMapper;
        this.seriesMapper = seriesMapper;
        this.aiModelMapper = aiModelMapper;
        this.genTaskMapper = genTaskMapper;
        this.textService = textService;
        this.generateService = generateService;
        this.assetService = assetService;
        this.storyboardService = storyboardService;
        this.characterReview = characterReview;
        this.creationService = creationService;
        this.mediaRetention = mediaRetention;
        this.objectStorage = objectStorage;
        this.encryptor = encryptor;
        this.imageDescribeClient = imageDescribeClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<DramaAssetResp> listByEpisode(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episode.getId())
                .orderByAsc(DramaAsset::getType)
                .orderByAsc(DramaAsset::getId));
        clearStaleTaskLinksOnLockedAssets(assets);
        clearOrphanedPreviewImages(assets);
        applySucceededQuietly(assets);
        repairIncompletePrompts(assets, episode.getSeriesId());
        Map<Long, GenTask> tasks = loadTasks(assets);
        Map<String, Integer> familyCounts = familyVersionCounts(assets);
        return assets.stream()
                .map(item -> toResp(item, item.getGenTaskId() == null ? null : tasks.get(item.getGenTaskId()),
                        familyCounts))
                .toList();
    }

    /**
     * A task id is only meaningful while an asset is awaiting its result.  Older
     * deployments could leave the id on a locked asset, causing every refresh to
     * expose the stale task's PENDING/RUNNING state as "generating".
     */
    private void clearStaleTaskLinksOnLockedAssets(List<DramaAsset> assets) {
        for (DramaAsset asset : assets) {
            if (DramaContract.STATUS_LOCKED.equals(asset.getStatus()) && asset.getGenTaskId() != null) {
                asset.setGenTaskId(null);
                assetMapper.updateById(asset);
            }
        }
    }

    /**
     * 删光生成记录时曾只清掉 selectedGenerationId，imageStorageKey 因未标 ALWAYS 未能落库。
     * 无选用记录却仍挂着预览图时，列表刷新顺带清掉，卡片回到「上传图片」空态。
     */
    private void clearOrphanedPreviewImages(List<DramaAsset> assets) {
        for (DramaAsset asset : assets) {
            if (asset.getSelectedGenerationId() != null) {
                continue;
            }
            if (!StringUtils.hasText(asset.getImageStorageKey()) && asset.getImageAssetId() == null) {
                continue;
            }
            clearSelectedImage(asset);
            assetMapper.updateById(asset);
        }
    }

    @Transactional
    public List<DramaAssetResp> extractFromScript(Long episodeId) {
        return extractFromScript(episodeId, true);
    }

    /**
     * 抽取剧本资产。分析闭环可关闭 LLM，使用确定性的本地规则快速完成首屏结果。
     */
    @Transactional
    public List<DramaAssetResp> extractFromScript(Long episodeId, boolean useLlm) {
        long started = System.nanoTime();
        log.info("[drama] 资产分析开始 episodeId={} useLlm={}", episodeId, useLlm);
        DramaEpisode episode = requireEpisode(episodeId);
        if (!DramaContract.STATUS_LOCKED.equals(episode.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先锁定分场剧本再提取资产");
        }
        ScriptDoc doc = ScriptDocs.fromMap(episode.getScript());
        if (doc == null || doc.getScenes() == null || doc.getScenes().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集没有可抽取的分场剧本");
        }
        DramaStyles.Spec style = DramaStyles.require(requireOwnedSeries(episode.getSeriesId()).getStyleKey());
        AssetDraftList drafts = DramaAssetDrafts.fromScript(doc, style);
        DramaTextService svc = textService.getIfAvailable();
        if (useLlm && svc != null) {
            drafts = svc.enrichAssets(episode.getSeriesId(), doc, drafts);
        } else if (useLlm) {
            log.warn("[drama] 文本服务不可用，保留确定性资产骨架 episodeId={}", episodeId);
        }
        DramaAssetDrafts.ensurePrompts(drafts, doc, style);
        if (drafts == null || drafts.getAssets() == null || drafts.getAssets().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未能从剧本抽出资产，请手工新增");
        }
        Long userId = UserContext.requireUserId();
        Map<String, DramaAsset> existing = existingBySeries(episode.getSeriesId());
        for (AssetDraftList.AssetDraft draft : drafts.getAssets()) {
            upsertDraft(episode, userId, existing, draft);
        }
        touchSeries(episode.getSeriesId());
        List<DramaAssetResp> result = listByEpisode(episode.getId());
        log.info("[drama] 资产分析完成 episodeId={} assets={} elapsedMs={}",
                episodeId, result.size(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    @Transactional
    public DramaAssetResp create(Long episodeId, DramaAssetSaveReq req) {
        DramaEpisode episode = requireEpisode(episodeId);
        if (!StringUtils.hasText(req.getName()) || !StringUtils.hasText(req.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写资产名称与类型");
        }
        String type = normalizeType(req.getType());
        String versionLabel = firstNonBlank(req.getVersionLabel(), req.getStateName());
        // 兼容旧前端：带状态名时若已有基础版，则延伸出新版本。
        if (DramaContract.ASSET_TYPE_CHARACTER.equals(type) && StringUtils.hasText(versionLabel)) {
            DramaAsset base = findFamilyBase(episode.getSeriesId(), episode.getId(), type,
                    DramaAssetDrafts.baseCharacterName(req.getName().trim()));
            if (base != null) {
                DramaAssetVersionReq versionReq = new DramaAssetVersionReq();
                versionReq.setVersionLabel(versionLabel.trim());
                versionReq.setChangeDescription(req.getDescription());
                versionReq.setParentAssetId(req.getParentAssetId() != null ? req.getParentAssetId() : base.getId());
                return createVersion(base.getId(), versionReq);
            }
        }
        DramaAssetVersions.ParsedName parsed = DramaAssetVersions.parseLegacyName(req.getName().trim());
        String name = parsed.familyName();
        if (DramaContract.ASSET_TYPE_CHARACTER.equals(type)) {
            DramaAssetVersions.validateFamilyName(name);
        }
        String stateTag = DramaContract.STATE_TAG_BASE;
        if (StringUtils.hasText(parsed.versionLabel())) {
            // 剧本式「洛白·湿身」直接新建时落成版本行；无基础版时一并建基础占位不在此路径。
            stateTag = DramaAssetVersions.slugFromLabel(parsed.versionLabel());
        } else if (StringUtils.hasText(req.getStateTag())
                && !DramaContract.STATE_TAG_BASE.equals(DramaContract.normalizeStateTag(req.getStateTag()))) {
            stateTag = DramaContract.normalizeStateTag(req.getStateTag());
        }
        if (!DramaAssetVersions.isBase(stateTag)
                && !DramaAssetDrafts.isChangeFact(req.getDescription())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写状态描述，例如「穿红色晚礼服，头发扎起」");
        }
        if (DramaAssetVersions.isBase(stateTag)) {
            rejectDuplicateFamilyBase(episode.getSeriesId(), type, name);
        } else {
            rejectDuplicateVersionLabel(episode.getSeriesId(), episode.getId(), type, name,
                    firstNonBlank(parsed.versionLabel(), versionLabel, DramaContract.stateWord(stateTag)));
        }
        DramaAsset asset = new DramaAsset();
        asset.setSeriesId(episode.getSeriesId());
        asset.setEpisodeId(episode.getId());
        asset.setUserId(UserContext.requireUserId());
        asset.setStatus(DramaContract.STATUS_NOT_STARTED);
        asset.setIdentityVersion(0);
        applySave(asset, req, true);
        asset.setType(type);
        asset.setName(name);
        asset.setStateTag(stateTag);
        if (!DramaAssetVersions.isBase(stateTag)) {
            asset.setMustSplit(true);
            asset.setVersionLabel(firstNonBlank(parsed.versionLabel(), versionLabel, DramaContract.stateWord(stateTag)));
            asset.setParentAssetId(req.getParentAssetId());
            if (asset.getParentAssetId() == null) {
                DramaAsset base = findFamilyBase(episode.getSeriesId(), episode.getId(), type, name);
                if (base != null) {
                    asset.setParentAssetId(base.getId());
                }
            }
            if (!StringUtils.hasText(asset.getIdentityInvariants())) {
                asset.setIdentityInvariants(DramaAssetDrafts.defaultIdentity(type, name, stateTag));
            }
        } else {
            asset.setVersionLabel(null);
            asset.setParentAssetId(null);
        }
        if (!StringUtils.hasText(asset.getDescription()) && StringUtils.hasText(asset.getIdentityInvariants())) {
            asset.setDescription(asset.getIdentityInvariants());
        }
        refreshCompiledImagePrompt(asset);
        ensureUnique(asset, null);
        assetMapper.insert(asset);
        touchSeries(episode.getSeriesId());
        return toResp(asset, null);
    }

    /**
     * 基于某版本延伸新建；同 {@code name}、新 slug、变化描述；参考图链指向父版。
     */
    @Transactional
    public DramaAssetResp createVersion(Long assetId, DramaAssetVersionReq req) {
        DramaAsset source = requireAsset(assetId);
        if (!DramaContract.ASSET_TYPE_CHARACTER.equals(source.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有角色可以延伸版本");
        }
        if (req == null || !StringUtils.hasText(req.getVersionLabel())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写版本标签");
        }
        DramaAssetVersions.validateVersionLabel(req.getVersionLabel());
        if (!DramaAssetDrafts.isChangeFact(req.getChangeDescription())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写状态描述，例如「穿红色晚礼服，头发扎起」");
        }
        String label = req.getVersionLabel().trim();
        String familyName = source.getName().trim();
        rejectDuplicateVersionLabel(source.getSeriesId(), source.getEpisodeId(), source.getType(), familyName, label);
        Long parentId = req.getParentAssetId() != null ? req.getParentAssetId() : source.getId();
        DramaAsset parent = requireAsset(parentId);
        if (!parent.getType().equals(source.getType())
                || !familyName.equals(parent.getName() == null ? "" : parent.getName().trim())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "父版本必须属于同一角色");
        }
        java.util.Set<String> usedSlugs = familyAssets(source.getSeriesId(), source.getEpisodeId(),
                source.getType(), familyName).stream()
                .map(item -> item.getStateTag() == null ? DramaContract.STATE_TAG_BASE : item.getStateTag().trim())
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        String stateTag = DramaAssetVersions.uniquifySlug(DramaAssetVersions.slugFromLabel(label), usedSlugs);
        DramaAsset asset = new DramaAsset();
        asset.setSeriesId(source.getSeriesId());
        asset.setEpisodeId(source.getEpisodeId());
        asset.setUserId(UserContext.requireUserId());
        asset.setName(familyName);
        asset.setType(source.getType());
        asset.setStateTag(stateTag);
        asset.setVersionLabel(label);
        asset.setParentAssetId(parent.getId());
        asset.setDescription(req.getChangeDescription().trim());
        asset.setIdentityInvariants(DramaAssetDrafts.defaultIdentity(
                source.getType(), familyName, stateTag));
        asset.setMustSplit(true);
        asset.setNeedsHuman(true);
        asset.setStatus(DramaContract.STATUS_NOT_STARTED);
        asset.setIdentityVersion(0);
        refreshCompiledImagePrompt(asset);
        ensureUnique(asset, null);
        assetMapper.insert(asset);
        touchSeries(source.getSeriesId());
        return toResp(asset, null);
    }

    @Transactional
    public DramaAssetResp update(Long assetId, DramaAssetSaveReq req) {
        DramaAsset asset = requireAsset(assetId);
        boolean appearanceChanged = req.getIdentityInvariants() != null
                || req.getName() != null
                || req.getStateTag() != null
                || req.getVersionLabel() != null
                || req.getType() != null
                // 版本的「变化描述」写在 description 上，改了要重算提示词。
                || req.getDescription() != null;
        String previousName = asset.getName();
        applySave(asset, req, false);
        if (req.getName() != null && DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())) {
            DramaAssetVersions.ParsedName parsed = DramaAssetVersions.parseLegacyName(req.getName().trim());
            DramaAssetVersions.validateFamilyName(parsed.familyName());
            asset.setName(parsed.familyName());
            if (StringUtils.hasText(parsed.versionLabel()) && DramaAssetVersions.isBase(asset.getStateTag())) {
                // 误把「洛白·湿身」写进基础版名：升为版本标签。
                asset.setVersionLabel(parsed.versionLabel());
                asset.setStateTag(parsed.stateTag());
                asset.setMustSplit(true);
            } else if (StringUtils.hasText(parsed.versionLabel()) && req.getVersionLabel() == null) {
                asset.setVersionLabel(parsed.versionLabel());
            }
            if (!ObjectsEquals(previousName, asset.getName())) {
                renameFamily(asset, previousName, asset.getName());
            }
        }
        if (req.getVersionLabel() != null && !DramaAssetVersions.isBase(asset.getStateTag())) {
            DramaAssetVersions.validateVersionLabel(req.getVersionLabel());
            rejectDuplicateVersionLabel(asset.getSeriesId(), asset.getEpisodeId(), asset.getType(),
                    asset.getName(), req.getVersionLabel().trim(), asset.getId());
            asset.setVersionLabel(req.getVersionLabel().trim());
        }
        ensureUnique(asset, asset.getId());
        reopenIfConfirmed(asset);
        if (appearanceChanged) {
            refreshCompiledImagePrompt(asset);
        }
        assetMapper.updateById(asset);
        touchSeries(asset.getSeriesId());
        return toResp(asset, loadTask(asset.getGenTaskId()));
    }

    private static boolean ObjectsEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    /** 改族名时同步同集同类型的其它版本行。 */
    private void renameFamily(DramaAsset pivot, String from, String to) {
        if (!StringUtils.hasText(from) || !StringUtils.hasText(to) || from.equals(to)) {
            return;
        }
        for (DramaAsset mate : familyAssets(pivot.getSeriesId(), pivot.getEpisodeId(), pivot.getType(), from)) {
            if (mate.getId().equals(pivot.getId())) {
                continue;
            }
            mate.setName(to);
            assetMapper.updateById(mate);
        }
    }

    public DramaAssetReviewResp reviewCharacter(Long assetId) {
        return characterReview.review(requireAsset(assetId));
    }

    @Transactional
    public void delete(Long assetId) {
        DramaAsset asset = requireAsset(assetId);
        if (isGenerating(loadTask(asset.getGenTaskId()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "生图进行中，请稍后再删");
        }
        List<DramaAsset> victims;
        if (DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())
                && DramaAssetVersions.isBase(asset.getStateTag())) {
            // 删基础版 = 删整族。
            victims = new java.util.ArrayList<>(familyAssets(
                    asset.getSeriesId(), asset.getEpisodeId(), asset.getType(), asset.getName()));
            if (victims.stream().noneMatch(item -> asset.getId().equals(item.getId()))) {
                victims.add(asset);
            }
            if (victims.isEmpty()) {
                victims = List.of(asset);
            }
        } else {
            victims = List.of(asset);
        }
        for (DramaAsset victim : victims) {
            if (isGenerating(loadTask(victim.getGenTaskId()))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "生图进行中，请稍后再删");
            }
        }
        java.util.LinkedHashSet<String> storageKeys = new java.util.LinkedHashSet<>();
        Long userId = asset.getUserId();
        Long episodeId = asset.getEpisodeId();
        Long seriesId = asset.getSeriesId();
        // 先删非基础版，再删基础，避免 parent 外键阻碍。
        List<DramaAsset> ordered = new java.util.ArrayList<>();
        for (DramaAsset victim : victims) {
            if (!DramaAssetVersions.isBase(victim.getStateTag())) {
                ordered.add(victim);
            }
        }
        for (DramaAsset victim : victims) {
            if (DramaAssetVersions.isBase(victim.getStateTag())) {
                ordered.add(victim);
            }
        }
        for (DramaAsset victim : ordered) {
            List<DramaAssetGeneration> generations = listGenerationEntities(victim.getId());
            if (generations != null) {
                for (DramaAssetGeneration generation : generations) {
                    if (generation != null && StringUtils.hasText(generation.getStorageKey())) {
                        storageKeys.add(generation.getStorageKey());
                    }
                }
            }
            if (StringUtils.hasText(victim.getImageStorageKey())) {
                storageKeys.add(victim.getImageStorageKey());
            }
            if (StringUtils.hasText(victim.getVoiceReferenceKey())) {
                storageKeys.add(victim.getVoiceReferenceKey());
            }
            forgetAssetCreations(generations);
            assetMapper.deleteById(victim.getId());
            storyboardService.detachAssetReference(episodeId, victim.getId());
        }
        touchSeries(seriesId);
        List<String> releasedKeys = List.copyOf(storageKeys);
        afterCommit(() -> releasedKeys.forEach(characterReview::release));
        mediaRetention.purgeAfterCommit(userId, releasedKeys);
    }

    @Transactional
    public DramaAssetResp uploadImage(Long assetId, MultipartFile file) {
        DramaAsset asset = requireAsset(assetId);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择图片");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!mime.startsWith("image/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资产参考图仅支持图片");
        }
        String key = assetService.putOwnedFile(file, "drama/" + asset.getSeriesId());
        insertAndSelectGeneration(asset, key, null, "upload", null, null);
        reopenIfConfirmed(asset);
        if (DramaContract.STATUS_NOT_STARTED.equals(asset.getStatus())) {
            asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        assetMapper.updateById(asset);
        touchSeries(asset.getSeriesId());
        return toResp(asset, loadTask(asset.getGenTaskId()));
    }

    @Transactional
    public DramaAssetResp bindImageFromLibrary(Long assetId, Long libraryAssetId) {
        DramaAsset asset = requireAsset(assetId);
        Asset libraryAsset = assetService.requireOwned(libraryAssetId);
        if (!AssetKind.IMAGE.equals(libraryAsset.getKind())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择图片素材");
        }
        if (!StringUtils.hasText(libraryAsset.getStorageKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "素材文件无效");
        }
        // source 使用 upload 以兼容尚未放开 library 的旧库；展示标签仍为「引用 N」
        DramaAssetGeneration generation = new DramaAssetGeneration();
        generation.setAssetId(asset.getId());
        generation.setUserId(asset.getUserId());
        generation.setStorageKey(libraryAsset.getStorageKey());
        generation.setGenTaskId(null);
        generation.setSource("upload");
        generation.setLabel(nextAssetLabel(asset.getId(), "library"));
        generation.setResolution(null);
        generation.setLibraryAssetId(libraryAsset.getId());
        assetGenerationMapper.insert(generation);
        applySelectedGeneration(asset, generation);
        reopenIfConfirmed(asset);
        if (DramaContract.STATUS_NOT_STARTED.equals(asset.getStatus())) {
            asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        assetMapper.updateById(asset);
        touchSeries(asset.getSeriesId());
        log.info("bind drama asset image assetId={} name={} libraryAssetId={} libraryAsset={}",
                asset.getId(), asset.getName(), libraryAsset.getId(), libraryAsset.getName());
        return toResp(asset, loadTask(asset.getGenTaskId()));
    }

    @Transactional
    public DramaAssetResp uploadVoiceReference(Long assetId, MultipartFile file) {
        DramaAsset asset = requireAsset(assetId);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择音频");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!mime.startsWith("audio/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "音色参考仅支持音频文件");
        }
        validateVoiceReferenceTarget(asset);
        AssetResp libraryAsset = assetService.upload(file);
        attachVoiceReference(asset, libraryAsset.getStorageKey(), libraryAsset.getMimeType());
        return toResp(asset, loadTask(asset.getGenTaskId()));
    }

    @Transactional
    public DramaAssetResp bindVoiceReference(Long assetId, Long libraryAssetId) {
        DramaAsset asset = requireAsset(assetId);
        validateVoiceReferenceTarget(asset);
        Asset libraryAsset = assetService.requireOwned(libraryAssetId);
        if (!AssetKind.AUDIO.equals(libraryAsset.getKind())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择音频素材");
        }
        attachVoiceReference(asset, libraryAsset.getStorageKey(), libraryAsset.getMimeType());
        log.info("bind drama voice reference characterAssetId={} character={} libraryAssetId={} libraryAsset={} mime={}",
                asset.getId(), asset.getName(), libraryAsset.getId(), libraryAsset.getName(), libraryAsset.getMimeType());
        return toResp(asset, loadTask(asset.getGenTaskId()));
    }

    private void validateVoiceReferenceTarget(DramaAsset asset) {
        if (!DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有角色资产可以设置音色参考");
        }
    }

    /** 音色挂在基础版；版本行请求时回写到族内 base。 */
    private DramaAsset voiceOwner(DramaAsset asset) {
        if (asset == null || DramaAssetVersions.isBase(asset.getStateTag())) {
            return asset;
        }
        DramaAsset base = findBaseCharacter(asset);
        return base != null ? base : asset;
    }

    private void attachVoiceReference(DramaAsset asset, String key, String mime) {
        DramaAsset target = voiceOwner(asset);
        target.setVoiceReferenceKey(key);
        target.setVoiceReferenceMime(mime);
        reopenIfConfirmed(target);
        assetMapper.updateById(target);
        touchSeries(target.getSeriesId());
    }

    @Transactional
    public DramaAssetGenerateResp generateImage(Long assetId) {
        return generateImage(assetId, null);
    }

    @Transactional
    public DramaAssetGenerateResp generateImage(Long assetId, String resolution) {
        DramaAsset asset = requireAsset(assetId);
        DramaSeries series = requireOwnedSeries(asset.getSeriesId());
        AiModel model = requireBoundImageModel(series);
        DramaAsset referenceSheet = findReferenceSheet(asset);
        DramaAsset baseCharacter = findBaseCharacter(asset);
        if (isStateCharacter(asset) && referenceSheet == null && baseCharacter != null) {
            // 父版/基础版都没有设定图：硬画会变成另一个人的新脸。
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "请先给基础版「" + asset.getName() + "」生成设定图，再生成延伸版");
        }
        String prompt;
        if (referenceSheet == null) {
            prompt = compileImagePromptForGenerate(asset, series);
        } else {
            String change = DramaAssetDrafts.derivativeChange(
                    DramaAssetVersions.mentionNameOf(asset),
                    asset.getStateTag(),
                    asset.getDescription(),
                    asset.getIdentityInvariants());
            String baseIdentity = baseCharacter != null ? baseCharacter.getIdentityInvariants() : null;
            prompt = DramaAssetDrafts.derivativePrompt(change, baseIdentity);
        }
        if (!StringUtils.hasText(prompt)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先完善外形设定");
        }
        assertGenerateCapacity(asset.getEpisodeId());
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(model.getId());
        req.setCategory(ModelCategory.IMAGE);
        req.setPrompt(trimPrompt(prompt));
        req.setRatio(series.getAspectRatio());
        req.setResolution(normalizeImageResolution(resolution));
        req.setCopies(1);
        if (referenceSheet != null) {
            GenerateSubmitReq.ReferenceInput input = new GenerateSubmitReq.ReferenceInput();
            input.setType(AssetKind.IMAGE);
            if (StringUtils.hasText(referenceSheet.getImageStorageKey())) {
                input.setStorageKey(referenceSheet.getImageStorageKey());
            } else {
                input.setAssetId(referenceSheet.getImageAssetId());
            }
            req.setReferences(List.of(input));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", GenerateRequests.SOURCE_DRAMA);
        extra.put("seriesId", series.getId());
        extra.put("episodeId", asset.getEpisodeId());
        extra.put("assetId", asset.getId());
        GenerateTaskResp task = generateService.submit(req, extra);
        asset.setGenTaskId(task.getId());
        reopenIfConfirmed(asset);
        assetMapper.updateById(asset);
        touchSeries(series.getId());
        DramaAssetGenerateResp resp = new DramaAssetGenerateResp();
        resp.setAsset(toResp(asset, null));
        resp.setTask(task);
        resp.getAsset().setGenerating(true);
        resp.getAsset().setGenTaskId(task.getId());
        resp.getAsset().setGenTaskStatus(task.getStatus());
        return resp;
    }

    /**
     * 外部上传/引用图 → 图生文 → Seedream 5.0 纯 T2I，落 generation source=roundtrip 并获得 origin 资格。
     * 图生文在事务外执行，避免长时间占用 DB 连接；submit 在短事务内完成。
     */
    public DramaAssetGenerateResp convertToSeedream(Long assetId, String resolution) {
        DramaAsset asset = requireAsset(assetId);
        DramaSeries series = requireOwnedSeries(asset.getSeriesId());
        if (!StringUtils.hasText(asset.getImageStorageKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先上传或引用参考图");
        }
        if (isGenerating(loadTask(asset.getGenTaskId()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资产正在生成中，请稍后再转");
        }
        AiModel model = requireBoundImageModel(series);
        requireSeedream50ForConvert(model);
        assertSameArkAccountForConvert(series, model);
        assertGenerateCapacity(asset.getEpisodeId());
        String storageKey = asset.getImageStorageKey();
        String apiKey = decryptModelApiKey(model);
        String endpoint = ArkUrls.v3Base(model.getEndpoint(), AdapterCatalog.ARK_DEFAULT_ENDPOINT, model.getProvider());
        byte[] imageBytes = objectStorage.getBytes(storageKey);
        String mime = mimeFromStorageKey(storageKey);
        String describePrompt = ImageDescribePrompts.forAssetType(asset.getType());
        ImageDescribeResult described = imageDescribeClient.describe(
                imageBytes, mime, describePrompt, endpoint, apiKey, null);
        String prompt = described == null ? "" : described.assembledPrompt();
        if (!StringUtils.hasText(prompt)) {
            throw new BizException(ErrorCode.INTERNAL, "图生文未得到可用提示词");
        }
        String trimmedPrompt = trimPrompt(prompt);
        String normalizedResolution = normalizeImageResolution(resolution);
        return transactionTemplate.execute(status ->
                submitConvertRoundtrip(assetId, storageKey, model.getId(), series.getId(),
                        series.getAspectRatio(), trimmedPrompt, normalizedResolution));
    }

    private DramaAssetGenerateResp submitConvertRoundtrip(
            Long assetId,
            String expectedStorageKey,
            Long modelId,
            Long seriesId,
            String aspectRatio,
            String prompt,
            String resolution) {
        DramaAsset asset = requireAsset(assetId);
        if (!StringUtils.hasText(asset.getImageStorageKey())
                || !expectedStorageKey.equals(asset.getImageStorageKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考图已变更，请重新转资产图");
        }
        if (isGenerating(loadTask(asset.getGenTaskId()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资产正在生成中，请稍后再转");
        }
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(modelId);
        req.setCategory(ModelCategory.IMAGE);
        req.setPrompt(prompt);
        req.setRatio(aspectRatio);
        req.setResolution(resolution);
        req.setCopies(1);
        // 故意不设 references：I2I 不写 SeedreamOriginUrls
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", GenerateRequests.SOURCE_DRAMA);
        extra.put("seriesId", seriesId);
        extra.put("episodeId", asset.getEpisodeId());
        extra.put("assetId", asset.getId());
        extra.put("assetGenerationSource", SOURCE_ROUNDTRIP);
        GenerateTaskResp task = generateService.submit(req, extra);
        asset.setGenTaskId(task.getId());
        reopenIfConfirmed(asset);
        assetMapper.updateById(asset);
        touchSeries(seriesId);
        log.info("convert-to-seedream assetId={} taskId={} storageKey={}",
                asset.getId(), task.getId(), asset.getImageStorageKey());
        DramaAssetGenerateResp resp = new DramaAssetGenerateResp();
        resp.setAsset(toResp(asset, null));
        resp.setTask(task);
        resp.getAsset().setGenerating(true);
        resp.getAsset().setGenTaskId(task.getId());
        resp.getAsset().setGenTaskStatus(task.getStatus());
        return resp;
    }

    @Transactional
    public DramaAssetGenerateResp getGenerateTask(Long assetId, Long taskId) {
        DramaAsset asset = requireAsset(assetId);
        if (asset.getGenTaskId() == null || !asset.getGenTaskId().equals(taskId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "生成任务不存在");
        }
        GenerateTaskResp task = generateService.getMine(taskId);
        applyTaskResult(asset, task);
        DramaAssetGenerateResp resp = new DramaAssetGenerateResp();
        resp.setAsset(toResp(asset, null));
        resp.setTask(task);
        resp.getAsset().setGenerating(isGeneratingStatus(task.getStatus()));
        resp.getAsset().setGenTaskStatus(task.getStatus());
        resp.getAsset().setGenErrorMessage(task.getErrorMessage());
        return resp;
    }

    @Transactional(readOnly = true)
    public List<DramaAssetGenerationResp> listGenerations(Long assetId) {
        DramaAsset asset = requireAsset(assetId);
        List<DramaAssetGeneration> generations = listGenerationEntities(asset.getId());
        Map<Long, String> modelNames = modelNamesByTaskId(generations);
        return generations.stream()
                .map(item -> toGenerationResp(item, asset.getSelectedGenerationId(), modelNames))
                .toList();
    }

    @Transactional
    public DramaAssetResp selectGeneration(Long assetId, Long generationId) {
        DramaAsset asset = requireAsset(assetId);
        DramaAssetGeneration generation = requireOwnedGeneration(asset, generationId);
        applySelectedGeneration(asset, generation);
        assetMapper.updateById(asset);
        storyboardService.invalidateByAsset(asset.getEpisodeId(), asset.getId());
        touchSeries(asset.getSeriesId());
        return toResp(asset, loadTask(asset.getGenTaskId()));
    }

    @Transactional
    public void deleteGeneration(Long assetId, Long generationId) {
        DramaAsset asset = requireAsset(assetId);
        DramaAssetGeneration generation = requireOwnedGeneration(asset, generationId);
        boolean selected = generation.getId().equals(asset.getSelectedGenerationId());
        String storageKey = generation.getStorageKey();
        boolean dropTask = generation.getGenTaskId() != null
                && generation.getGenTaskId().equals(asset.getGenTaskId());
        DramaAssetGeneration next = null;
        if (selected) {
            next = listGenerationEntities(asset.getId()).stream()
                    .filter(item -> item != null && !generation.getId().equals(item.getId()))
                    .findFirst()
                    .orElse(null);
            if (next != null) {
                applySelectedGeneration(asset, next);
            } else {
                clearSelectedImage(asset);
            }
            storyboardService.invalidateByAsset(asset.getEpisodeId(), asset.getId());
        }
        // 删掉的若正是资产上挂着的成功任务，列表刷新会把它再插回来并重新选中。
        // 任务号改到仍选用的那条上，刷新时就没有可回写的结果。
        if (dropTask) {
            asset.setGenTaskId(selected
                    ? (next == null ? null : next.getGenTaskId())
                    : taskIdOfGeneration(asset.getSelectedGenerationId()));
        }
        if (selected || dropTask) {
            assetMapper.updateById(asset);
        }
        assetGenerationMapper.deleteById(generation.getId());
        forgetCreation(generation.getUserId(), generation.getGenTaskId(), storageKey);
        afterCommit(() -> characterReview.release(storageKey));
        mediaRetention.purgeAfterCommit(generation.getUserId(), List.of(storageKey));
        touchSeries(asset.getSeriesId());
    }

    private void forgetAssetCreations(List<DramaAssetGeneration> generations) {
        if (generations == null) {
            return;
        }
        for (DramaAssetGeneration generation : generations) {
            if (generation == null) {
                continue;
            }
            forgetCreation(generation.getUserId(), generation.getGenTaskId(), generation.getStorageKey());
        }
    }

    private void forgetCreation(Long userId, Long taskId, String storageKey) {
        if (userId == null || taskId == null || !StringUtils.hasText(storageKey)) {
            return;
        }
        creationService.removeDramaOutputs(userId, taskId, storageKey);
    }

    @Transactional
    public DramaAssetGenerationResp importGenerationToLibrary(Long assetId, Long generationId) {
        DramaAsset asset = requireAsset(assetId);
        DramaAssetGeneration generation = requireOwnedGeneration(asset, generationId);
        if (generation.getLibraryAssetId() != null) {
            return toGenerationResp(generation, asset.getSelectedGenerationId(), modelNamesByTaskId(List.of(generation)));
        }
        String name = asset.getName() + " · " + generation.getLabel();
        AssetResp library = assetService.importFromStorage(
                UserContext.requireUserId(), name, AssetKind.IMAGE, generation.getStorageKey(), generation.getStorageKey());
        generation.setLibraryAssetId(library.getId());
        assetGenerationMapper.updateById(generation);
        if (generation.getId().equals(asset.getSelectedGenerationId())) {
            asset.setImageAssetId(library.getId());
            assetMapper.updateById(asset);
        }
        touchSeries(asset.getSeriesId());
        return toGenerationResp(generation, asset.getSelectedGenerationId(), modelNamesByTaskId(List.of(generation)));
    }

    @Transactional
    public void expireByEpisode(Long episodeId) {
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episodeId));
        for (DramaAsset asset : assets) {
            if (DramaContract.STATUS_EXPIRED.equals(asset.getStatus())) {
                continue;
            }
            asset.setStatus(DramaContract.STATUS_EXPIRED);
            assetMapper.updateById(asset);
        }
    }

    private void upsertDraft(DramaEpisode episode, Long userId, Map<String, DramaAsset> existing,
                             AssetDraftList.AssetDraft draft) {
        if (draft == null || !StringUtils.hasText(draft.getName())) {
            return;
        }
        String type = normalizeType(draft.getType());
        DramaAssetVersions.ParsedName parsed = DramaContract.ASSET_TYPE_CHARACTER.equals(type)
                ? DramaAssetVersions.parseLegacyName(draft.getName().trim())
                : new DramaAssetVersions.ParsedName(draft.getName().trim(), "", DramaContract.STATE_TAG_BASE);
        String name = parsed.familyName();
        String stateTag = StringUtils.hasText(draft.getStateTag())
                ? DramaContract.normalizeStateTag(draft.getStateTag())
                : (StringUtils.hasText(parsed.versionLabel())
                ? parsed.stateTag()
                : DramaAssetDrafts.inferStateTag(draft.getName()));
        String versionLabel = StringUtils.hasText(parsed.versionLabel())
                ? parsed.versionLabel()
                : (DramaAssetVersions.isBase(stateTag) ? null : DramaContract.stateWord(stateTag));
        String key = uniqueKey(type, name, stateTag);
        DramaAsset current = existing.get(key);
        if (current != null) {
            // 其他章节已经有同名资产时直接复用，不另插一行。
            if (DramaContract.STATUS_LOCKED.equals(current.getStatus())) {
                return;
            }
            fillEmpty(current, draft, type, stateTag);
            if (!StringUtils.hasText(current.getVersionLabel()) && StringUtils.hasText(versionLabel)) {
                current.setVersionLabel(versionLabel);
            }
            assetMapper.updateById(current);
            return;
        }
        DramaAsset asset = new DramaAsset();
        asset.setSeriesId(episode.getSeriesId());
        asset.setEpisodeId(episode.getId());
        asset.setUserId(userId);
        asset.setName(name);
        asset.setType(type);
        asset.setStateTag(stateTag);
        asset.setVersionLabel(versionLabel);
        if (!DramaAssetVersions.isBase(stateTag)) {
            DramaAsset base = findFamilyBase(episode.getSeriesId(), episode.getId(), type, name);
            if (base != null) {
                asset.setParentAssetId(base.getId());
            }
        }
        asset.setDescription(blankToNull(draft.getDescription()));
        asset.setIdentityInvariants(blankToNull(draft.getIdentityInvariants()));
        asset.setImagePrompt(blankToNull(draft.getImagePrompt()));
        asset.setVoiceLock(blankToNull(draft.getVoiceLock()));
        asset.setMustSplit(Boolean.TRUE.equals(draft.getMustSplit())
                || !DramaContract.STATE_TAG_BASE.equals(stateTag));
        asset.setNeedsHuman(draft.getNeedsHuman() == null || Boolean.TRUE.equals(draft.getNeedsHuman()));
        asset.setStatus(DramaContract.STATUS_NOT_STARTED);
        asset.setIdentityVersion(0);
        assetMapper.insert(asset);
        existing.put(key, asset);
    }

    private void fillEmpty(DramaAsset current, AssetDraftList.AssetDraft draft, String type, String stateTag) {
        DramaStyles.Spec style = DramaStyles.require(requireOwnedSeries(current.getSeriesId()).getStyleKey());
        // 异步增强会再次 upsert：允许用 LLM 描述覆盖确定性骨架模板，不覆盖用户手改长文。
        if ((!StringUtils.hasText(current.getDescription())
                || DramaAssetDrafts.shouldReplaceImagePrompt(
                current.getDescription(), type, style.key()))
                && StringUtils.hasText(draft.getDescription())) {
            current.setDescription(draft.getDescription().trim());
        }
        // 骨架句或场景/道具误套角色外形句，允许被 LLM 摘要覆盖。
        boolean identityOpen = DramaAssetDrafts.isSkeletonIdentity(current.getName(), current.getIdentityInvariants())
                || DramaAssetDrafts.isCharacterIdentityMisapplied(type, current.getIdentityInvariants());
        if (identityOpen
                && StringUtils.hasText(draft.getIdentityInvariants())
                && !DramaAssetDrafts.isSkeletonIdentity(current.getName(), draft.getIdentityInvariants())
                && !DramaAssetDrafts.isCharacterIdentityMisapplied(type, draft.getIdentityInvariants())) {
            current.setIdentityInvariants(draft.getIdentityInvariants().trim());
        } else if (identityOpen
                && DramaContract.ASSET_TYPE_CHARACTER.equals(type)
                && DramaAssetDrafts.isAppearanceBlurb(draft.getDescription())) {
            current.setIdentityInvariants(draft.getDescription().trim());
        } else if (DramaAssetDrafts.isCharacterIdentityMisapplied(type, current.getIdentityInvariants())) {
            current.setIdentityInvariants(DramaAssetDrafts.defaultIdentity(type, current.getName(), stateTag));
        }
        if ((!StringUtils.hasText(current.getImagePrompt())
                || DramaAssetDrafts.shouldReplaceImagePrompt(
                current.getImagePrompt(), type, style.key()))
                && StringUtils.hasText(draft.getImagePrompt())) {
            current.setImagePrompt(draft.getImagePrompt().trim());
            if (!StringUtils.hasText(current.getDescription())
                    || DramaAssetDrafts.shouldReplaceImagePrompt(
                    current.getDescription(), type, style.key())) {
                current.setDescription(current.getImagePrompt());
            }
        }
        if (!StringUtils.hasText(current.getVoiceLock()) && StringUtils.hasText(draft.getVoiceLock())) {
            current.setVoiceLock(draft.getVoiceLock().trim());
        }
        current.setType(type);
        current.setStateTag(stateTag);
        if (Boolean.TRUE.equals(draft.getMustSplit()) || !DramaContract.STATE_TAG_BASE.equals(stateTag)) {
            current.setMustSplit(true);
        }
        if (DramaContract.STATUS_NOT_STARTED.equals(current.getStatus())
                || DramaContract.STATUS_EXPIRED.equals(current.getStatus())) {
            current.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
    }

    private void applySave(DramaAsset asset, DramaAssetSaveReq req, boolean creating) {
        if (req.getName() != null || creating) {
            if (!StringUtils.hasText(req.getName())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "资产名称不能为空");
            }
            asset.setName(req.getName().trim());
        }
        if (req.getType() != null || creating) {
            asset.setType(normalizeType(req.getType()));
        }
        if (req.getStateTag() != null || creating) {
            String tag = StringUtils.hasText(req.getStateTag())
                    ? req.getStateTag().trim()
                    : DramaContract.STATE_TAG_BASE;
            asset.setStateTag(DramaContract.normalizeStateTag(tag));
        }
        if (req.getVersionLabel() != null) {
            asset.setVersionLabel(blankToNull(req.getVersionLabel()));
        }
        if (req.getParentAssetId() != null) {
            asset.setParentAssetId(req.getParentAssetId());
        }
        if (req.getDescription() != null) {
            asset.setDescription(blankToNull(req.getDescription()));
        }
        if (req.getIdentityInvariants() != null) {
            asset.setIdentityInvariants(blankToNull(req.getIdentityInvariants()));
        }
        if (req.getImagePrompt() != null) {
            // imagePrompt 仅作系统拼装缓存/高级预览；用户主编辑外形设定（identity），不再用长模板覆盖 description。
            asset.setImagePrompt(blankToNull(req.getImagePrompt()));
        }
        if (req.getVoiceLock() != null) {
            asset.setVoiceLock(blankToNull(req.getVoiceLock()));
        }
        if (req.getMustSplit() != null) {
            asset.setMustSplit(req.getMustSplit());
        } else if (asset.getMustSplit() == null) {
            asset.setMustSplit(!DramaContract.STATE_TAG_BASE.equals(asset.getStateTag()));
        }
        if (req.getNeedsHuman() != null) {
            asset.setNeedsHuman(req.getNeedsHuman());
        } else if (asset.getNeedsHuman() == null) {
            asset.setNeedsHuman(true);
        }
        if (!StringUtils.hasText(asset.getStateTag())) {
            asset.setStateTag(DramaContract.STATE_TAG_BASE);
        }
    }

    private void ensureUnique(DramaAsset asset, Long excludeId) {
        DramaAsset clash = assetMapper.selectOne(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, asset.getEpisodeId())
                .eq(DramaAsset::getType, asset.getType())
                .eq(DramaAsset::getName, asset.getName())
                .eq(DramaAsset::getStateTag, asset.getStateTag())
                .ne(excludeId != null, DramaAsset::getId, excludeId)
                .last("LIMIT 1"));
        if (clash != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "同名同版本的资产已存在");
        }
    }

    private void attachImage(DramaAsset asset, Long imageAssetId, String storageKey) {
        asset.setImageAssetId(imageAssetId);
        asset.setImageStorageKey(storageKey);
    }

    private void applySucceededQuietly(List<DramaAsset> assets) {
        for (DramaAsset asset : assets) {
            if (asset.getGenTaskId() == null) {
                continue;
            }
            try {
                GenerateTaskResp task = generateService.getMine(asset.getGenTaskId());
                applyTaskResult(asset, task);
            } catch (BizException ignored) {
                // 任务不属于当前用户或已删：列表仍返回资产本身
            }
        }
    }

    private void applyTaskResult(DramaAsset asset, GenerateTaskResp task) {
        if (task == null || !TaskStatus.SUCCEEDED.equals(task.getStatus())) {
            return;
        }
        String key = firstOutputKey(task);
        if (!StringUtils.hasText(key) || key.equals(asset.getImageStorageKey())) {
            return;
        }
        // 任务结果可能已入库；用户改选旧记录后 imageStorageKey 与最新任务不一致，
        // 刷新/轮询时不得再插入并强行改选。
        if (findRecordedAssetGeneration(asset.getId(), task.getId(), key) != null) {
            return;
        }
        String resolution = resolutionFromTask(task);
        insertAndSelectGeneration(asset, key, task.getId(), generationSourceFromTask(task), resolution, null);
        reopenIfConfirmed(asset);
        if (DramaContract.STATUS_NOT_STARTED.equals(asset.getStatus())) {
            asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        assetMapper.updateById(asset);
    }

    /** 同一任务或同一图片已有记录则视为已落库（避免改选后被任务回写重复插入）。 */
    private DramaAssetGeneration findRecordedAssetGeneration(Long assetId, Long genTaskId, String storageKey) {
        if (genTaskId != null) {
            DramaAssetGeneration byTask = assetGenerationMapper.selectOne(new LambdaQueryWrapper<DramaAssetGeneration>()
                    .eq(DramaAssetGeneration::getAssetId, assetId)
                    .eq(DramaAssetGeneration::getGenTaskId, genTaskId)
                    .last("LIMIT 1"));
            if (byTask != null) {
                return byTask;
            }
        }
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        return assetGenerationMapper.selectOne(new LambdaQueryWrapper<DramaAssetGeneration>()
                .eq(DramaAssetGeneration::getAssetId, assetId)
                .eq(DramaAssetGeneration::getStorageKey, storageKey)
                .last("LIMIT 1"));
    }

    private void insertAndSelectGeneration(
            DramaAsset asset,
            String storageKey,
            Long genTaskId,
            String source,
            String resolution,
            Long libraryAssetId) {
        DramaAssetGeneration generation = new DramaAssetGeneration();
        generation.setAssetId(asset.getId());
        generation.setUserId(asset.getUserId());
        generation.setStorageKey(storageKey);
        generation.setGenTaskId(genTaskId);
        generation.setSource(source);
        generation.setLabel(nextAssetLabel(asset.getId(), source));
        generation.setResolution(blankToNull(resolution));
        generation.setLibraryAssetId(libraryAssetId);
        assetGenerationMapper.insert(generation);
        applySelectedGeneration(asset, generation);
    }

    private void applySelectedGeneration(DramaAsset asset, DramaAssetGeneration generation) {
        asset.setSelectedGenerationId(generation.getId());
        attachImage(asset, generation.getLibraryAssetId(), generation.getStorageKey());
    }

    private void clearSelectedImage(DramaAsset asset) {
        asset.setSelectedGenerationId(null);
        attachImage(asset, null, null);
    }

    private Long taskIdOfGeneration(Long generationId) {
        if (generationId == null) {
            return null;
        }
        DramaAssetGeneration current = assetGenerationMapper.selectById(generationId);
        return current == null ? null : current.getGenTaskId();
    }

    private DramaAssetGeneration requireOwnedGeneration(DramaAsset asset, Long generationId) {
        DramaAssetGeneration generation = assetGenerationMapper.selectById(generationId);
        if (generation == null
                || !generation.getAssetId().equals(asset.getId())
                || !generation.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "生成记录不存在");
        }
        return generation;
    }

    private List<DramaAssetGeneration> listGenerationEntities(Long assetId) {
        return assetGenerationMapper.selectList(new LambdaQueryWrapper<DramaAssetGeneration>()
                .eq(DramaAssetGeneration::getAssetId, assetId)
                .orderByDesc(DramaAssetGeneration::getId));
    }

    private String nextAssetLabel(Long assetId, String source) {
        long count = assetGenerationMapper.selectCount(new LambdaQueryWrapper<DramaAssetGeneration>()
                .eq(DramaAssetGeneration::getAssetId, assetId)
                .eq(DramaAssetGeneration::getSource, source));
        long next = count + 1;
        if ("upload".equals(source)) {
            return "上传 " + next;
        }
        if ("library".equals(source)) {
            return "引用 " + next;
        }
        if (SOURCE_ROUNDTRIP.equals(source)) {
            return "转资产图 " + next;
        }
        return "生成方案 " + next;
    }

    private static String generationSourceFromTask(GenerateTaskResp task) {
        if (task == null || task.getParams() == null || task.getParams().get("assetGenerationSource") == null) {
            return "generate";
        }
        String source = String.valueOf(task.getParams().get("assetGenerationSource")).trim();
        if (SOURCE_ROUNDTRIP.equals(source)) {
            return SOURCE_ROUNDTRIP;
        }
        return "generate";
    }

    private void requireSeedream50ForConvert(AiModel model) {
        String adapterKey = AdapterCatalog.resolveKey(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        if (!SeedreamOriginUrls.isSeedream50(adapterKey, model.getModelKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "转资产图须绑定 Seedream 5.0 系列图像模型");
        }
    }

    /**
     * Seedance 只信任同账号 Seedream 5.0 原 HTTPS。双方都是方舟官方类时，要求解密后 Key 一致。
     */
    private void assertSameArkAccountForConvert(DramaSeries series, AiModel imageModel) {
        if (series == null || series.getVideoModelId() == null || imageModel == null) {
            return;
        }
        if (!isArkOfficialProvider(imageModel.getProvider())) {
            return;
        }
        AiModel videoModel = aiModelMapper.selectById(series.getVideoModelId());
        if (videoModel == null || !isArkOfficialProvider(videoModel.getProvider())) {
            return;
        }
        String imageKey = decryptModelApiKey(imageModel);
        String videoKey = decryptModelApiKey(videoModel);
        if (!imageKey.equals(videoKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "转资产图与视频模型须使用同一方舟 API Key，否则 Seedance 不信任 origin");
        }
    }

    private static boolean isArkOfficialProvider(String provider) {
        if (!StringUtils.hasText(provider)) {
            return false;
        }
        String value = provider.trim();
        return AdapterCatalog.PROVIDER_OFFICIAL.equals(value)
                || AdapterCatalog.PROVIDER_OFFICIAL_TOKEN_PLAN.equals(value)
                || value.contains("官方API");
    }

    private String decryptModelApiKey(AiModel model) {
        if (model == null || !StringUtils.hasText(model.getApiKeyEnc())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请在管理端填写火山方舟 API Key");
        }
        String decrypted = encryptor.decrypt(model.getApiKeyEnc());
        String key = ArkKeys.normalize(decrypted);
        if (!StringUtils.hasText(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请在管理端填写火山方舟 API Key");
        }
        return key;
    }

    static String mimeFromStorageKey(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return "image/jpeg";
        }
        String lower = storageKey.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private DramaAssetGenerationResp toGenerationResp(
            DramaAssetGeneration generation,
            Long selectedId,
            Map<Long, String> modelNames) {
        DramaAssetGenerationResp resp = new DramaAssetGenerationResp();
        resp.setId(generation.getId());
        resp.setAssetId(generation.getAssetId());
        resp.setStorageKey(generation.getStorageKey());
        resp.setUrl(MediaUrls.of(generation.getStorageKey()));
        resp.setGenTaskId(generation.getGenTaskId());
        resp.setSource(generation.getSource());
        resp.setLabel(generation.getLabel());
        resp.setResolution(generation.getResolution());
        resp.setModelName(modelNameOf(generation, modelNames));
        resp.setLibraryAssetId(generation.getLibraryAssetId());
        resp.setSelected(generation.getId().equals(selectedId));
        resp.setImported(generation.getLibraryAssetId() != null);
        resp.setCreatedAt(generation.getCreatedAt());
        ArkAssetRefs.Ref review = characterReview.lookup(generation.getStorageKey());
        if (review != null && StringUtils.hasText(review.status())) {
            resp.setArkReviewStatus(review.status());
            resp.setArkReviewError(blankToNull(review.error()));
        }
        SeedreamOriginUrls.OriginInfo origin = SeedreamOriginUrls.inspect(
                objectStorage, objectMapper, generation.getStorageKey(), Instant.now());
        resp.setOriginStatus(origin.status());
        if (origin.expiresAt() != null) {
            resp.setOriginExpiresAt(OffsetDateTime.ofInstant(origin.expiresAt(), ZoneOffset.UTC));
        }
        return resp;
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String resolutionFromTask(GenerateTaskResp task) {
        if (task.getParams() == null || task.getParams().get("resolution") == null) {
            return null;
        }
        return blankToNull(String.valueOf(task.getParams().get("resolution")));
    }

    private void reopenIfConfirmed(DramaAsset asset) {
        if (DramaContract.STATUS_LOCKED.equals(asset.getStatus())
                || DramaContract.STATUS_EXPIRED.equals(asset.getStatus())) {
            asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            storyboardService.invalidateByAsset(asset.getEpisodeId(), asset.getId());
        }
    }

    private void assertGenerateCapacity(Long episodeId) {
        Long running = genTaskMapper.selectCount(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getUserId, UserContext.requireUserId())
                .in(GenTask::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING)
                .apply("params->>'source' = {0}", GenerateRequests.SOURCE_DRAMA)
                .apply("params->>'episodeId' = {0}", String.valueOf(episodeId)));
        if (running != null && running >= DramaContract.MAX_ASSET_GENERATE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "同时生图不能超过 " + DramaContract.MAX_ASSET_GENERATE + " 条，请稍后再试");
        }
    }

    private AiModel requireBoundImageModel(DramaSeries series) {
        if (series.getImageModelId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先选择图像模型");
        }
        AiModel model = aiModelMapper.selectById(series.getImageModelId());
        if (model == null || !ModelCategory.IMAGE.equalsIgnoreCase(model.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目绑定的图像模型不存在");
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目绑定的图像模型已停用，请在资产页更换");
        }
        if (!GenerateRequests.supportsMode(model.getCapabilities(), ModelCategory.IMAGE, GenerateMode.T2I)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前图像模型不支持文生图");
        }
        return model;
    }

    private DramaEpisode requireEpisode(Long id) {
        DramaEpisode episode = episodeMapper.selectById(id);
        if (episode == null || !episode.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        return episode;
    }

    private DramaAsset requireAsset(Long id) {
        DramaAsset asset = assetMapper.selectById(id);
        if (asset == null || !asset.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        return asset;
    }

    private DramaSeries requireOwnedSeries(Long id) {
        DramaSeries series = seriesMapper.selectById(id);
        if (series == null || !series.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        return series;
    }

    /** 整部剧范围内按类型、名称、状态认资产。已有多条时留最早的那条。 */
    private Map<String, DramaAsset> existingBySeries(Long seriesId) {
        Map<String, DramaAsset> map = new LinkedHashMap<>();
        if (seriesId == null) {
            return map;
        }
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getSeriesId, seriesId)
                .orderByAsc(DramaAsset::getId));
        for (DramaAsset asset : assets) {
            map.putIfAbsent(uniqueKey(asset.getType(), asset.getName(), asset.getStateTag()), asset);
        }
        return map;
    }

    /** 同一分类下基础名已有基础版则拒绝。 */
    private void rejectDuplicateFamilyBase(Long seriesId, String type, String name) {
        if (seriesId == null || !StringUtils.hasText(name)) {
            return;
        }
        String wanted = name.trim();
        boolean exists = existingBySeries(seriesId).values().stream()
                .anyMatch(asset -> asset != null
                        && type.equals(asset.getType())
                        && wanted.equals(asset.getName() == null ? "" : asset.getName().trim())
                        && DramaAssetVersions.isBase(asset.getStateTag()));
        if (exists) {
            throw new BizException(ErrorCode.BAD_REQUEST, duplicateAssetMessage(type));
        }
    }

    private void rejectDuplicateVersionLabel(Long seriesId, Long episodeId, String type, String name, String label) {
        rejectDuplicateVersionLabel(seriesId, episodeId, type, name, label, null);
    }

    private void rejectDuplicateVersionLabel(
            Long seriesId, Long episodeId, String type, String name, String label, Long excludeId) {
        if (!StringUtils.hasText(label)) {
            return;
        }
        String wanted = label.trim();
        for (DramaAsset asset : familyAssets(seriesId, episodeId, type, name)) {
            if (excludeId != null && excludeId.equals(asset.getId())) {
                continue;
            }
            if (DramaAssetVersions.isBase(asset.getStateTag())) {
                continue;
            }
            String existing = StringUtils.hasText(asset.getVersionLabel())
                    ? asset.getVersionLabel().trim()
                    : DramaContract.stateWord(asset.getStateTag());
            if (wanted.equals(existing)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "「" + name + "」的「" + wanted + "」版本已经存在");
            }
        }
    }

    static String duplicateAssetMessage(String type, String name) {
        DramaAssetVersions.ParsedName parsed = DramaAssetVersions.parseLegacyName(name);
        if (StringUtils.hasText(parsed.versionLabel())) {
            return "「" + parsed.familyName() + "」的「" + parsed.versionLabel() + "」版本已经存在";
        }
        return duplicateAssetMessage(type);
    }

    private List<DramaAsset> familyAssets(Long seriesId, Long episodeId, String type, String name) {
        if (!StringUtils.hasText(name) || !StringUtils.hasText(type)) {
            return List.of();
        }
        return assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(seriesId != null, DramaAsset::getSeriesId, seriesId)
                .eq(seriesId == null && episodeId != null, DramaAsset::getEpisodeId, episodeId)
                .eq(DramaAsset::getType, type)
                .eq(DramaAsset::getName, name.trim())
                .orderByAsc(DramaAsset::getId));
    }

    private DramaAsset findFamilyBase(Long seriesId, Long episodeId, String type, String name) {
        for (DramaAsset asset : familyAssets(seriesId, episodeId, type, name)) {
            if (DramaAssetVersions.isBase(asset.getStateTag())) {
                return asset;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    static String duplicateAssetMessage(String type) {
        if (DramaContract.ASSET_TYPE_CHARACTER.equals(type)) {
            return "该角色已经存在";
        }
        if (DramaContract.ASSET_TYPE_PROP.equals(type)) {
            return "该道具已经存在";
        }
        if (DramaContract.ASSET_TYPE_SCENE.equals(type)) {
            return "该场景已经存在";
        }
        return "该资产已经存在";
    }

    /** 按生成记录上的任务号反查当时的模型展示名，键是 gen_task.id。 */
    private Map<Long, String> modelNamesByTaskId(List<DramaAssetGeneration> generations) {
        Set<Long> taskIds = new HashSet<>();
        for (DramaAssetGeneration generation : generations) {
            if (generation.getGenTaskId() != null) {
                taskIds.add(generation.getGenTaskId());
            }
        }
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> modelIdByTask = new HashMap<>();
        Set<Long> modelIds = new HashSet<>();
        for (GenTask task : genTaskMapper.selectBatchIds(taskIds)) {
            if (task.getId() == null || task.getModelId() == null) {
                continue;
            }
            modelIdByTask.put(task.getId(), task.getModelId());
            modelIds.add(task.getModelId());
        }
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> namesByModel = new HashMap<>();
        for (AiModel model : aiModelMapper.selectBatchIds(modelIds)) {
            if (model.getId() != null && StringUtils.hasText(model.getName())) {
                namesByModel.put(model.getId(), model.getName().trim());
            }
        }
        Map<Long, String> namesByTask = new HashMap<>();
        for (Map.Entry<Long, Long> entry : modelIdByTask.entrySet()) {
            String name = namesByModel.get(entry.getValue());
            if (StringUtils.hasText(name)) {
                namesByTask.put(entry.getKey(), name);
            }
        }
        return namesByTask;
    }

    private static String modelNameOf(DramaAssetGeneration generation, Map<Long, String> modelNames) {
        if (generation.getGenTaskId() == null || modelNames == null) {
            return null;
        }
        return modelNames.get(generation.getGenTaskId());
    }

    private Map<Long, GenTask> loadTasks(List<DramaAsset> assets) {
        Set<Long> ids = new HashSet<>();
        for (DramaAsset asset : assets) {
            if (asset.getGenTaskId() != null) {
                ids.add(asset.getGenTaskId());
            }
        }
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, GenTask> tasks = new HashMap<>();
        for (GenTask task : genTaskMapper.selectBatchIds(ids)) {
            tasks.put(task.getId(), task);
        }
        return tasks;
    }

    private GenTask loadTask(Long taskId) {
        return taskId == null ? null : genTaskMapper.selectById(taskId);
    }

    private void touchSeries(Long seriesId) {
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series != null) {
            seriesMapper.updateById(series);
        }
    }

    private DramaAssetResp toResp(DramaAsset asset, GenTask task) {
        return toResp(asset, task, null);
    }

    private DramaAssetResp toResp(DramaAsset asset, GenTask task, Map<String, Integer> familyCounts) {
        DramaAssetResp resp = new DramaAssetResp();
        resp.setId(asset.getId());
        resp.setSeriesId(asset.getSeriesId());
        resp.setEpisodeId(asset.getEpisodeId());
        resp.setName(asset.getName());
        resp.setType(asset.getType());
        resp.setStateTag(asset.getStateTag());
        String displayLabel = DramaAssetVersions.displayLabel(asset.getStateTag(), asset.getVersionLabel());
        resp.setVersionLabel(displayLabel);
        // 兼容旧前端：仅有明确 versionLabel 的延伸版才填 stateLabel。
        resp.setStateLabel(StringUtils.hasText(asset.getVersionLabel()) ? asset.getVersionLabel().trim() : null);
        resp.setParentAssetId(asset.getParentAssetId());
        resp.setMentionName(DramaAssetVersions.mentionNameOf(asset));
        if (familyCounts != null && DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())) {
            resp.setVersionCount(familyCounts.getOrDefault(familyKey(asset), 1));
        } else {
            resp.setVersionCount(1);
        }
        resp.setDescription(asset.getDescription());
        resp.setIdentityInvariants(asset.getIdentityInvariants());
        resp.setImagePrompt(asset.getImagePrompt());
        resp.setVoiceLock(asset.getVoiceLock());
        resp.setVoiceReferenceKey(asset.getVoiceReferenceKey());
        resp.setVoiceReferenceUrl(MediaUrls.of(asset.getVoiceReferenceKey()));
        resp.setVoiceReferenceMime(asset.getVoiceReferenceMime());
        resp.setMustSplit(Boolean.TRUE.equals(asset.getMustSplit()));
        resp.setNeedsHuman(Boolean.TRUE.equals(asset.getNeedsHuman()));
        resp.setStatus(asset.getStatus());
        resp.setIdentityVersion(asset.getIdentityVersion() == null ? 0 : asset.getIdentityVersion());
        resp.setImageStorageKey(asset.getImageStorageKey());
        resp.setImageAssetId(asset.getImageAssetId());
        resp.setImageUrl(MediaUrls.of(asset.getImageStorageKey()));
        resp.setSelectedGenerationId(asset.getSelectedGenerationId());
        resp.setGenTaskId(asset.getGenTaskId());
        if (task != null) {
            resp.setGenTaskStatus(task.getStatus());
            resp.setGenErrorMessage(task.getErrorMessage());
            resp.setGenerating(isGenerating(task));
        } else {
            resp.setGenerating(false);
        }
        resp.setReferenceEligible(StringUtils.hasText(asset.getImageStorageKey()));
        resp.setCreatedAt(asset.getCreatedAt());
        resp.setUpdatedAt(asset.getUpdatedAt());
        return resp;
    }

    private static String familyKey(DramaAsset asset) {
        return (asset.getType() == null ? "" : asset.getType())
                + "\0"
                + (asset.getName() == null ? "" : asset.getName().trim());
    }

    private static Map<String, Integer> familyVersionCounts(List<DramaAsset> assets) {
        Map<String, Integer> counts = new HashMap<>();
        if (assets == null) {
            return counts;
        }
        for (DramaAsset asset : assets) {
            if (asset == null || !DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())) {
                continue;
            }
            String key = familyKey(asset);
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    static String normalizeType(String type) {
        String value = type == null ? "" : type.trim();
        if (!DramaContract.ASSET_TYPES.contains(value)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资产类型仅支持 角色、场景、道具");
        }
        return value;
    }

    static String normalizeImageResolution(String value) {
        if (!StringUtils.hasText(value)) {
            return "1K";
        }
        String resolution = value.trim().toUpperCase();
        if ("2K".equals(resolution) || "1K".equals(resolution)) {
            return resolution;
        }
        if ("720P".equals(resolution) || "480P".equals(resolution)) {
            return "1K";
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "出图分辨率仅支持 1K、2K");
    }

    static String uniqueKey(String type, String name, String stateTag) {
        return type + "\0" + name + "\0" + (StringUtils.hasText(stateTag) ? stateTag : DramaContract.STATE_TAG_BASE);
    }

    private void repairIncompletePrompts(List<DramaAsset> assets, Long seriesId) {
        if (assets == null || assets.isEmpty()) {
            return;
        }
        DramaSeries series = requireOwnedSeries(seriesId);
        DramaStyles.Spec style = DramaStyles.require(series.getStyleKey());
        for (DramaAsset asset : assets) {
            if (asset == null || DramaContract.STATUS_LOCKED.equals(asset.getStatus())) {
                continue;
            }
            boolean changed = false;
            if (DramaAssetDrafts.isCharacterIdentityMisapplied(asset.getType(), asset.getIdentityInvariants())) {
                asset.setIdentityInvariants(DramaAssetDrafts.defaultIdentity(
                        asset.getType(), asset.getName(), asset.getStateTag()));
                changed = true;
            }
            if (DramaAssetDrafts.shouldReplaceImagePrompt(
                    asset.getImagePrompt(), asset.getType(), style.key())) {
                applyCompiledImagePrompt(asset, style);
                changed = true;
            } else if (changed) {
                applyCompiledImagePrompt(asset, style);
            }
            if (changed) {
                assetMapper.updateById(asset);
            }
        }
    }

    /**
     * 方案 A：生图始终按当前风格模板 + 外形设定（identity）重拼，不采用用户库里的整段 imagePrompt 底稿。
     * 拼好的长句回写 imagePrompt 仅供页面「完整提示词」预览。
     */
    private String compileImagePromptForGenerate(DramaAsset asset, DramaSeries series) {
        DramaStyles.Spec style = DramaStyles.require(series.getStyleKey());
        DramaAsset base = findBaseCharacter(asset);
        boolean derivative = base != null;
        String identity = identityForPrompt(asset, base);
        // 延伸版：模板不注入整段外形（避免「外形与服装」措辞）；约束改用核心身份。
        String rebuilt = DramaAssetDrafts.buildPrompt(
                asset.getType(), asset.getName(), asset.getStateTag(), style, null,
                derivative ? null : identity);
        rebuilt = withIdentityConstraint(rebuilt, asset.getType(), identity, derivative);
        rebuilt = withStateChange(rebuilt, asset);
        if (StringUtils.hasText(rebuilt) && !rebuilt.equals(blankToNull(asset.getImagePrompt()))) {
            asset.setImagePrompt(rebuilt);
            scrubTemplateDescription(asset, style);
            assetMapper.updateById(asset);
        }
        return rebuilt;
    }

    /** 状态资产没有本体图可参考时，把状态描述也写进提示词，否则这一状态只剩模板话。 */
    private static String withStateChange(String prompt, DramaAsset asset) {
        if (asset == null || DramaContract.STATE_TAG_BASE.equals(asset.getStateTag())) {
            return prompt;
        }
        if (!DramaAssetDrafts.isChangeFact(asset.getDescription())) {
            return prompt;
        }
        return prompt + "本状态外观：" + asset.getDescription().trim() + "。";
    }

    /** 创建/更新外形后刷新完整提示词缓存，供侧栏只读预览。 */
    private void refreshCompiledImagePrompt(DramaAsset asset) {
        if (asset == null) {
            return;
        }
        DramaStyles.Spec style = DramaStyles.require(requireOwnedSeries(asset.getSeriesId()).getStyleKey());
        applyCompiledImagePrompt(asset, style);
    }

    private void applyCompiledImagePrompt(DramaAsset asset, DramaStyles.Spec style) {
        DramaAsset base = findBaseCharacter(asset);
        boolean derivative = base != null;
        String identity = identityForPrompt(asset, base);
        String rebuilt = DramaAssetDrafts.buildPrompt(
                asset.getType(), asset.getName(), asset.getStateTag(), style, null,
                derivative ? null : identity);
        rebuilt = withIdentityConstraint(rebuilt, asset.getType(), identity, derivative);
        rebuilt = withStateChange(rebuilt, asset);
        asset.setImagePrompt(rebuilt);
        scrubTemplateDescription(asset, style);
    }

    /** description 若仍是旧版整段模板，降级为外形摘要，避免与 identity 双源长句。 */
    private static void scrubTemplateDescription(DramaAsset asset, DramaStyles.Spec style) {
        if (asset == null || style == null) {
            return;
        }
        // 状态资产的 description 是「相对本体的变化」，不是设定图模板，不能被降级掉。
        if (!DramaContract.STATE_TAG_BASE.equals(asset.getStateTag())
                && DramaAssetDrafts.isChangeFact(asset.getDescription())) {
            return;
        }
        if (!DramaAssetDrafts.shouldReplaceImagePrompt(asset.getDescription(), asset.getType(), style.key())) {
            return;
        }
        asset.setDescription(StringUtils.hasText(asset.getIdentityInvariants())
                ? asset.getIdentityInvariants().trim()
                : null);
    }

    /**
     * 延伸版参考图：父版有图 → 父版；否则族内基础版有图 → 基础版；否则 null 走整张设定图。
     */
    private DramaAsset findReferenceSheet(DramaAsset asset) {
        if (asset == null || DramaAssetVersions.isBase(asset.getStateTag())) {
            return null;
        }
        if (asset.getParentAssetId() != null) {
            DramaAsset parent = assetMapper.selectById(asset.getParentAssetId());
            if (parent != null
                    && (StringUtils.hasText(parent.getImageStorageKey()) || parent.getImageAssetId() != null)) {
                return parent;
            }
        }
        DramaAsset base = findBaseCharacter(asset);
        if (base != null
                && (StringUtils.hasText(base.getImageStorageKey()) || base.getImageAssetId() != null)) {
            return base;
        }
        return null;
    }

    /** 兼容旧调用名。 */
    private DramaAsset findBaseCharacterSheet(DramaAsset asset) {
        return findReferenceSheet(asset);
    }

    /** 版本资产的基础版（同系列、同类型、同名、state=base），有没有图都返回。 */
    private DramaAsset findBaseCharacter(DramaAsset asset) {
        if (asset == null
                || !DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())
                || asset.getEpisodeId() == null) {
            return null;
        }
        if (DramaAssetVersions.isBase(asset.getStateTag())) {
            return null;
        }
        String baseName = asset.getName() == null ? "" : asset.getName().trim();
        if (!StringUtils.hasText(baseName)) {
            baseName = DramaAssetDrafts.baseCharacterName(asset.getName());
        }
        if (!StringUtils.hasText(baseName)) {
            return null;
        }
        return findFamilyBase(asset.getSeriesId(), asset.getEpisodeId(), DramaContract.ASSET_TYPE_CHARACTER, baseName);
    }

    /** 是不是角色状态资产（本体名·状态名，非 base）。 */
    private static boolean isStateCharacter(DramaAsset asset) {
        if (asset == null || !DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())) {
            return false;
        }
        String tag = StringUtils.hasText(asset.getStateTag())
                ? asset.getStateTag().trim()
                : DramaAssetDrafts.inferStateTag(asset.getName());
        return !DramaContract.STATE_TAG_BASE.equals(tag);
    }

    /**
     * 基础版用自身完整外形；延伸版只继承基础版核心身份（脸型/体型等），服装与发型发色不锁。
     */
    private static String identityForPrompt(DramaAsset asset, DramaAsset base) {
        String own = asset == null ? null : asset.getIdentityInvariants();
        if (base == null || !StringUtils.hasText(base.getIdentityInvariants())) {
            return own;
        }
        return DramaAssetDrafts.coreIdentityOnly(base.getIdentityInvariants());
    }

    static String resolveImagePrompt(DramaAsset asset) {
        if (StringUtils.hasText(asset.getImagePrompt())) {
            return asset.getImagePrompt().trim();
        }
        StringBuilder prompt = new StringBuilder();
        if (StringUtils.hasText(asset.getDescription())) {
            prompt.append(asset.getDescription().trim());
        }
        if (StringUtils.hasText(asset.getIdentityInvariants())) {
            if (!prompt.isEmpty()) {
                prompt.append("。");
            }
            prompt.append(asset.getIdentityInvariants().trim());
        }
        return prompt.toString();
    }

    /** 生图时把设定摘要并进 prompt，避免服装/发型只写在摘要里却未进入模型。 */
    static String withIdentityConstraint(String prompt, String identity) {
        return withIdentityConstraint(prompt, DramaContract.ASSET_TYPE_CHARACTER, identity, false);
    }

    static String withIdentityConstraint(String prompt, String type, String identity) {
        return withIdentityConstraint(prompt, type, identity, false);
    }

    /**
     * @param coreFaceBodyOnly 延伸版为 true：约束「面部与体型」，不要求服装与基础版一致
     */
    static String withIdentityConstraint(String prompt, String type, String identity, boolean coreFaceBodyOnly) {
        if (!StringUtils.hasText(prompt) || !StringUtils.hasText(identity)) {
            return prompt;
        }
        String id = identity.trim();
        if (DramaAssetDrafts.isSkeletonIdentity(null, id)
                || DramaAssetDrafts.isCharacterIdentityMisapplied(type, id)) {
            return prompt;
        }
        if (prompt.contains(id)) {
            return prompt;
        }
        if (DramaContract.ASSET_TYPE_SCENE.equals(type)) {
            return prompt + "空间陈设必须符合：" + id + "。";
        }
        if (DramaContract.ASSET_TYPE_PROP.equals(type)) {
            return prompt + "外形材质必须符合：" + id + "。";
        }
        if (coreFaceBodyOnly) {
            return prompt + "面部与体型必须符合：" + id + "。";
        }
        return prompt + "外形与服装必须符合：" + id + "。";
    }

    static String trimPrompt(String prompt) {
        String value = prompt.trim();
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }

    static String firstOutputKey(GenerateTaskResp task) {
        if (task.getOutputKeys() != null) {
            for (String key : task.getOutputKeys()) {
                if (StringUtils.hasText(key)) {
                    return key;
                }
            }
        }
        return task.getOutputStorageKey();
    }

    static boolean isGenerating(GenTask task) {
        return task != null && isGeneratingStatus(task.getStatus());
    }

    static boolean isGeneratingStatus(String status) {
        return TaskStatus.PENDING.equals(status) || TaskStatus.RUNNING.equals(status);
    }

    static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
