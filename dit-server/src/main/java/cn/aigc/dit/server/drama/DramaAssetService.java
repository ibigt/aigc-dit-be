package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaAssetGeneration;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.req.DramaAssetSaveReq;
import cn.aigc.dit.server.drama.model.resp.DramaAssetGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetGenerationResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetResp;
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
import cn.aigc.dit.server.service.GenerateService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class DramaAssetService {

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

    @Transactional
    public List<DramaAssetResp> listByEpisode(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episode.getId())
                .orderByAsc(DramaAsset::getType)
                .orderByAsc(DramaAsset::getId));
        applySucceededQuietly(assets);
        repairIncompletePrompts(assets, episode.getSeriesId());
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
        Map<String, DramaAsset> existing = existingByKey(episode.getId());
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
        DramaAsset asset = new DramaAsset();
        asset.setSeriesId(episode.getSeriesId());
        asset.setEpisodeId(episode.getId());
        asset.setUserId(UserContext.requireUserId());
        asset.setStatus(DramaContract.STATUS_NOT_STARTED);
        asset.setIdentityVersion(0);
        applySave(asset, req, true);
        DramaStyles.Spec style = DramaStyles.require(requireOwnedSeries(episode.getSeriesId()).getStyleKey());
        if (!StringUtils.hasText(asset.getImagePrompt())
                || DramaAssetDrafts.shouldReplaceImagePrompt(
                asset.getImagePrompt(), asset.getType(), style.key())) {
            asset.setImagePrompt(DramaAssetDrafts.buildPrompt(
                    asset.getType(), asset.getName(), asset.getStateTag(), style, null, asset.getIdentityInvariants()));
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

    private void attachVoiceReference(DramaAsset asset, String key, String mime) {
        asset.setVoiceReferenceKey(key);
        asset.setVoiceReferenceMime(mime);
        reopenIfConfirmed(asset);
        assetMapper.updateById(asset);
        touchSeries(asset.getSeriesId());
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
        String prompt = withIdentityConstraint(completeImagePrompt(asset, series), asset.getIdentityInvariants());
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
        String prompt = completeImagePrompt(asset, requireOwnedSeries(asset.getSeriesId()));
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

    @Transactional(readOnly = true)
    public List<DramaAssetGenerationResp> listGenerations(Long assetId) {
        DramaAsset asset = requireAsset(assetId);
        return listGenerationEntities(asset.getId()).stream()
                .map(item -> toGenerationResp(item, asset.getSelectedGenerationId()))
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
        assetGenerationMapper.deleteById(generation.getId());
        if (!selected) {
            touchSeries(asset.getSeriesId());
            return;
        }
        DramaAssetGeneration next = listGenerationEntities(asset.getId()).stream().findFirst().orElse(null);
        if (next != null) {
            applySelectedGeneration(asset, next);
        } else {
            clearSelectedImage(asset);
        }
        assetMapper.updateById(asset);
        storyboardService.invalidateByAsset(asset.getEpisodeId(), asset.getId());
        touchSeries(asset.getSeriesId());
    }

    @Transactional
    public DramaAssetGenerationResp importGenerationToLibrary(Long assetId, Long generationId) {
        DramaAsset asset = requireAsset(assetId);
        DramaAssetGeneration generation = requireOwnedGeneration(asset, generationId);
        if (generation.getLibraryAssetId() != null) {
            return toGenerationResp(generation, asset.getSelectedGenerationId());
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
        return toGenerationResp(generation, asset.getSelectedGenerationId());
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
        DramaStyles.Spec style = DramaStyles.require(requireOwnedSeries(current.getSeriesId()).getStyleKey());
        // 异步增强会再次 upsert：允许用 LLM 描述覆盖确定性骨架模板，不覆盖用户手改长文。
        if ((!StringUtils.hasText(current.getDescription())
                || DramaAssetDrafts.shouldReplaceImagePrompt(
                current.getDescription(), type, style.key()))
                && StringUtils.hasText(draft.getDescription())) {
            current.setDescription(draft.getDescription().trim());
        }
        if ((!StringUtils.hasText(current.getIdentityInvariants())
                || DramaAssetDrafts.shouldReplaceImagePrompt(
                current.getIdentityInvariants(), type, style.key()))
                && StringUtils.hasText(draft.getIdentityInvariants())) {
            current.setIdentityInvariants(draft.getIdentityInvariants().trim());
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
            // 清空或改写 imagePrompt 时同步 description，避免 resolveImagePrompt 回落到旧描述。
            if (StringUtils.hasText(asset.getImagePrompt())) {
                asset.setDescription(asset.getImagePrompt());
            } else if (!StringUtils.hasText(req.getImagePrompt())) {
                asset.setDescription(null);
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
        // 任务结果可能已入库；用户改选旧记录后 imageStorageKey 与最新任务不一致，
        // 刷新/轮询时不得再插入并强行改选。
        if (findRecordedAssetGeneration(asset.getId(), task.getId(), key) != null) {
            return;
        }
        String resolution = resolutionFromTask(task);
        insertAndSelectGeneration(asset, key, task.getId(), "generate", resolution, null);
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
        return "生成方案 " + next;
    }

    private DramaAssetGenerationResp toGenerationResp(DramaAssetGeneration generation, Long selectedId) {
        DramaAssetGenerationResp resp = new DramaAssetGenerationResp();
        resp.setId(generation.getId());
        resp.setAssetId(generation.getAssetId());
        resp.setStorageKey(generation.getStorageKey());
        resp.setUrl(MediaUrls.of(generation.getStorageKey()));
        resp.setGenTaskId(generation.getGenTaskId());
        resp.setSource(generation.getSource());
        resp.setLabel(generation.getLabel());
        resp.setResolution(generation.getResolution());
        resp.setLibraryAssetId(generation.getLibraryAssetId());
        resp.setSelected(generation.getId().equals(selectedId));
        resp.setImported(generation.getLibraryAssetId() != null);
        resp.setCreatedAt(generation.getCreatedAt());
        return resp;
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
            if (!DramaAssetDrafts.shouldReplaceImagePrompt(
                    asset.getImagePrompt(), asset.getType(), style.key())) {
                continue;
            }
            String rebuilt = DramaAssetDrafts.buildPrompt(
                    asset.getType(), asset.getName(), asset.getStateTag(), style, null, asset.getIdentityInvariants());
            asset.setImagePrompt(rebuilt);
            if (!StringUtils.hasText(asset.getDescription())
                    || DramaAssetDrafts.shouldReplaceImagePrompt(
                    asset.getDescription(), asset.getType(), style.key())) {
                asset.setDescription(rebuilt);
            }
            assetMapper.updateById(asset);
        }
    }

    private String completeImagePrompt(DramaAsset asset, DramaSeries series) {
        DramaStyles.Spec style = DramaStyles.require(series.getStyleKey());
        String prompt = resolveImagePrompt(asset);
        if (!DramaAssetDrafts.shouldReplaceImagePrompt(prompt, asset.getType(), style.key())) {
            return prompt;
        }
        String rebuilt = DramaAssetDrafts.buildPrompt(
                asset.getType(), asset.getName(), asset.getStateTag(), style, null, asset.getIdentityInvariants());
        if (StringUtils.hasText(rebuilt) && !rebuilt.equals(prompt)) {
            asset.setImagePrompt(rebuilt);
            if (!StringUtils.hasText(asset.getDescription())
                    || DramaAssetDrafts.shouldReplaceImagePrompt(
                    asset.getDescription(), asset.getType(), style.key())) {
                asset.setDescription(rebuilt);
            }
            assetMapper.updateById(asset);
        }
        return rebuilt;
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
        if (!StringUtils.hasText(prompt) || !StringUtils.hasText(identity)) {
            return prompt;
        }
        String id = identity.trim();
        if (prompt.contains(id)) {
            return prompt;
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
