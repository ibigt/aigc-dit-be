package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.req.DramaAssetSaveReq;
import cn.aigc.dit.server.drama.model.resp.DramaAssetGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetResp;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.service.AssetService;
import cn.aigc.dit.server.service.GenerateService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DramaAssetService {

    private final DramaAssetMapper assetMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSeriesMapper seriesMapper;
    private final AiModelMapper aiModelMapper;
    private final GenTaskMapper genTaskMapper;
    private final ObjectProvider<DramaTextService> textService;
    private final GenerateService generateService;
    private final AssetService assetService;
    private final DramaStoryboardService storyboardService;

    @Transactional
    public List<DramaAssetResp> listByEpisode(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episode.getId())
                .orderByAsc(DramaAsset::getType)
                .orderByAsc(DramaAsset::getId));
        applySucceededQuietly(assets);
        Map<Long, GenTask> tasks = loadTasks(assets);
        return assets.stream()
                .map(item -> toResp(item, item.getGenTaskId() == null ? null : tasks.get(item.getGenTaskId())))
                .toList();
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
        DramaEpisode episode = requireEpisode(episodeId);
        if (!DramaContract.STATUS_LOCKED.equals(episode.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先锁定分场剧本再提取资产");
        }
        ScriptDoc doc = ScriptDocs.fromMap(episode.getScript());
        if (doc == null || doc.getScenes() == null || doc.getScenes().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集没有可抽取的分场剧本");
        }
        DramaStyles.Spec style = DramaStyles.require(requireOwnedSeries(episode.getSeriesId()).getStyleKey());
        AssetDraftList drafts;
        DramaTextService svc = textService.getIfAvailable();
        if (useLlm && svc != null) {
            drafts = svc.extractAssets(episode.getSeriesId(), doc);
            DramaAssetDrafts.ensurePrompts(drafts, doc, style);
        } else {
            drafts = DramaAssetDrafts.fromScript(doc, style);
        }
        if (drafts == null || drafts.getAssets() == null || drafts.getAssets().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未能从剧本抽出资产，请手工新增");
        }
        Long userId = UserContext.requireUserId();
        Map<String, DramaAsset> existing = existingByKey(episode.getId());
        for (AssetDraftList.AssetDraft draft : drafts.getAssets()) {
            upsertDraft(episode, userId, existing, draft);
        }
        touchSeries(episode.getSeriesId());
        return listByEpisode(episode.getId());
    }

    @Transactional
    public DramaAssetResp create(Long episodeId, DramaAssetSaveReq req) {
        DramaEpisode episode = requireEpisode(episodeId);
        if (!StringUtils.hasText(req.getName()) || !StringUtils.hasText(req.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写资产名称与类型");
        }
        DramaAsset asset = new DramaAsset();
        asset.setSeriesId(episode.getSeriesId());
        asset.setEpisodeId(episode.getId());
        asset.setUserId(UserContext.requireUserId());
        asset.setStatus(DramaContract.STATUS_NOT_STARTED);
        asset.setIdentityVersion(0);
        applySave(asset, req, true);
        if (!StringUtils.hasText(asset.getImagePrompt())) {
            DramaStyles.Spec style = DramaStyles.require(requireOwnedSeries(episode.getSeriesId()).getStyleKey());
            asset.setImagePrompt(DramaAssetDrafts.buildPrompt(asset.getType(), asset.getName(), style));
            asset.setDescription(asset.getImagePrompt());
        }
        ensureUnique(asset, null);
        assetMapper.insert(asset);
        touchSeries(episode.getSeriesId());
        return toResp(asset, null);
    }

    @Transactional
    public DramaAssetResp update(Long assetId, DramaAssetSaveReq req) {
        DramaAsset asset = requireAsset(assetId);
        applySave(asset, req, false);
        ensureUnique(asset, asset.getId());
        reopenIfConfirmed(asset);
        assetMapper.updateById(asset);
        touchSeries(asset.getSeriesId());
        return toResp(asset, loadTask(asset.getGenTaskId()));
    }

    @Transactional
    public void delete(Long assetId) {
        DramaAsset asset = requireAsset(assetId);
        if (isGenerating(loadTask(asset.getGenTaskId()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "生图进行中，请稍后再删");
        }
        assetMapper.deleteById(asset.getId());
        // 删除资产后同步解除分镜参考绑定，保留分镜文字中的道具描述。
        storyboardService.detachAssetReference(asset.getEpisodeId(), asset.getId());
        touchSeries(asset.getSeriesId());
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
        Long previousLibraryId = replaceableLibraryId(asset);
        attachImage(asset, null, key);
        reopenIfConfirmed(asset);
        if (DramaContract.STATUS_NOT_STARTED.equals(asset.getStatus())) {
            asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        assetMapper.updateById(asset);
        deleteReplacedLibrary(previousLibraryId);
        touchSeries(asset.getSeriesId());
        return toResp(asset, loadTask(asset.getGenTaskId()));
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
        String prompt = resolveImagePrompt(asset);
        if (!StringUtils.hasText(prompt)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先填写提示词");
        }
        assertGenerateCapacity(asset.getEpisodeId());
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(model.getId());
        req.setCategory(ModelCategory.IMAGE);
        req.setPrompt(trimPrompt(prompt));
        req.setRatio(series.getAspectRatio());
        req.setResolution(normalizeImageResolution(resolution));
        req.setCopies(1);
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

    @Transactional
    public DramaAssetResp lock(Long assetId) {
        DramaAsset asset = requireAsset(assetId);
        if (DramaContract.STATUS_LOCKED.equals(asset.getStatus())) {
            return toResp(asset, loadTask(asset.getGenTaskId()));
        }
        if (!StringUtils.hasText(asset.getImageStorageKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先上传或生成参考图再锁定");
        }
        String prompt = resolveImagePrompt(asset);
        if (!StringUtils.hasText(prompt)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先填写提示词再锁定");
        }
        if (!StringUtils.hasText(asset.getDescription())) {
            asset.setDescription(prompt);
        }
        if (!StringUtils.hasText(asset.getIdentityInvariants())) {
            asset.setIdentityInvariants(prompt);
        }
        int version = asset.getIdentityVersion() == null ? 0 : asset.getIdentityVersion();
        asset.setIdentityVersion(version + 1);
        asset.setStatus(DramaContract.STATUS_LOCKED);
        asset.setNeedsHuman(false);
        assetMapper.updateById(asset);
        storyboardService.invalidateByAsset(asset.getEpisodeId(), asset.getId());
        touchSeries(asset.getSeriesId());
        return toResp(asset, loadTask(asset.getGenTaskId()));
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
        String name = draft.getName().trim();
        String stateTag = StringUtils.hasText(draft.getStateTag())
                ? draft.getStateTag().trim()
                : DramaAssetDrafts.inferStateTag(name);
        String key = uniqueKey(type, name, stateTag);
        DramaAsset current = existing.get(key);
        if (current != null) {
            if (DramaContract.STATUS_LOCKED.equals(current.getStatus())) {
                return;
            }
            fillEmpty(current, draft, type, stateTag);
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
        if (!StringUtils.hasText(current.getDescription()) && StringUtils.hasText(draft.getDescription())) {
            current.setDescription(draft.getDescription().trim());
        }
        if (!StringUtils.hasText(current.getIdentityInvariants()) && StringUtils.hasText(draft.getIdentityInvariants())) {
            current.setIdentityInvariants(draft.getIdentityInvariants().trim());
        }
        if ((!StringUtils.hasText(current.getImagePrompt())
                || DramaAssetDrafts.shouldReplaceImagePrompt(current.getImagePrompt()))
                && StringUtils.hasText(draft.getImagePrompt())) {
            current.setImagePrompt(draft.getImagePrompt().trim());
            if (!StringUtils.hasText(current.getDescription())
                    || DramaAssetDrafts.shouldReplaceImagePrompt(current.getDescription())) {
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
                    : DramaAssetDrafts.inferStateTag(asset.getName());
            asset.setStateTag(tag);
        }
        if (req.getDescription() != null) {
            asset.setDescription(blankToNull(req.getDescription()));
        }
        if (req.getIdentityInvariants() != null) {
            asset.setIdentityInvariants(blankToNull(req.getIdentityInvariants()));
        }
        if (req.getImagePrompt() != null) {
            asset.setImagePrompt(blankToNull(req.getImagePrompt()));
            if (StringUtils.hasText(asset.getImagePrompt())) {
                asset.setDescription(asset.getImagePrompt());
            }
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
            throw new BizException(ErrorCode.BAD_REQUEST, "同名同状态的资产已存在，入梦/换装请用独立名称");
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
        Long previousLibraryId = replaceableLibraryId(asset);
        attachImage(asset, null, key);
        reopenIfConfirmed(asset);
        if (DramaContract.STATUS_NOT_STARTED.equals(asset.getStatus())) {
            asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        assetMapper.updateById(asset);
        deleteReplacedLibrary(previousLibraryId);
    }

    /** 未锁定草稿被换图时，历史素材库记录可以删；已锁定图不跟着清。新图一律不入素材库。 */
    private Long replaceableLibraryId(DramaAsset asset) {
        if (DramaContract.STATUS_LOCKED.equals(asset.getStatus())
                || DramaContract.STATUS_EXPIRED.equals(asset.getStatus())) {
            return null;
        }
        return asset.getImageAssetId();
    }

    private void deleteReplacedLibrary(Long previousId) {
        if (previousId == null) {
            return;
        }
        try {
            assetService.deleteMine(previousId);
        } catch (BizException ignored) {
            // 用户可能已从素材库删掉旧图
        }
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

    private Map<String, DramaAsset> existingByKey(Long episodeId) {
        Map<String, DramaAsset> map = new LinkedHashMap<>();
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episodeId));
        for (DramaAsset asset : assets) {
            map.put(uniqueKey(asset.getType(), asset.getName(), asset.getStateTag()), asset);
        }
        return map;
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
        DramaAssetResp resp = new DramaAssetResp();
        resp.setId(asset.getId());
        resp.setSeriesId(asset.getSeriesId());
        resp.setEpisodeId(asset.getEpisodeId());
        resp.setName(asset.getName());
        resp.setType(asset.getType());
        resp.setStateTag(asset.getStateTag());
        resp.setDescription(asset.getDescription());
        resp.setIdentityInvariants(asset.getIdentityInvariants());
        resp.setImagePrompt(asset.getImagePrompt());
        resp.setVoiceLock(asset.getVoiceLock());
        resp.setMustSplit(Boolean.TRUE.equals(asset.getMustSplit()));
        resp.setNeedsHuman(Boolean.TRUE.equals(asset.getNeedsHuman()));
        resp.setStatus(asset.getStatus());
        resp.setIdentityVersion(asset.getIdentityVersion() == null ? 0 : asset.getIdentityVersion());
        resp.setImageStorageKey(asset.getImageStorageKey());
        resp.setImageAssetId(asset.getImageAssetId());
        resp.setImageUrl(MediaUrls.of(asset.getImageStorageKey()));
        resp.setGenTaskId(asset.getGenTaskId());
        if (task != null) {
            resp.setGenTaskStatus(task.getStatus());
            resp.setGenErrorMessage(task.getErrorMessage());
            resp.setGenerating(isGenerating(task));
        } else {
            resp.setGenerating(false);
        }
        resp.setReferenceEligible(DramaContract.STATUS_LOCKED.equals(asset.getStatus())
                && StringUtils.hasText(asset.getImageStorageKey()));
        resp.setCreatedAt(asset.getCreatedAt());
        resp.setUpdatedAt(asset.getUpdatedAt());
        return resp;
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
