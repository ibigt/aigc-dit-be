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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
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
    private final ArkCharacterReview characterReview;
    private final CreationService creationService;
    private final MediaRetentionService mediaRetention;

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
                             MediaRetentionService mediaRetention) {
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
    }

    @Transactional
    public List<DramaAssetResp> listByEpisode(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episode.getId())
                .orderByAsc(DramaAsset::getType)
                .orderByAsc(DramaAsset::getId));
        clearStaleTaskLinksOnLockedAssets(assets);
        applySucceededQuietly(assets);
        repairIncompletePrompts(assets, episode.getSeriesId());
        Map<Long, GenTask> tasks = loadTasks(assets);
        return assets.stream()
                .map(item -> toResp(item, item.getGenTaskId() == null ? null : tasks.get(item.getGenTaskId())))
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
        String name = req.getName().trim();
        rejectDuplicateName(episode.getSeriesId(), type, name);
        DramaAsset asset = new DramaAsset();
        asset.setSeriesId(episode.getSeriesId());
        asset.setEpisodeId(episode.getId());
        asset.setUserId(UserContext.requireUserId());
        asset.setStatus(DramaContract.STATUS_NOT_STARTED);
        asset.setIdentityVersion(0);
        applySave(asset, req, true);
        if (!StringUtils.hasText(asset.getDescription()) && StringUtils.hasText(asset.getIdentityInvariants())) {
            asset.setDescription(asset.getIdentityInvariants());
        }
        refreshCompiledImagePrompt(asset);
        ensureUnique(asset, null);
        assetMapper.insert(asset);
        touchSeries(episode.getSeriesId());
        return toResp(asset, null);
    }

    @Transactional
    public DramaAssetResp update(Long assetId, DramaAssetSaveReq req) {
        DramaAsset asset = requireAsset(assetId);
        boolean appearanceChanged = req.getIdentityInvariants() != null
                || req.getName() != null
                || req.getStateTag() != null
                || req.getType() != null;
        applySave(asset, req, false);
        ensureUnique(asset, asset.getId());
        reopenIfConfirmed(asset);
        if (appearanceChanged) {
            refreshCompiledImagePrompt(asset);
        }
        assetMapper.updateById(asset);
        touchSeries(asset.getSeriesId());
        return toResp(asset, loadTask(asset.getGenTaskId()));
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
        List<DramaAssetGeneration> generations = listGenerationEntities(asset.getId());
        java.util.LinkedHashSet<String> storageKeys = new java.util.LinkedHashSet<>();
        if (generations != null) {
            for (DramaAssetGeneration generation : generations) {
                if (generation != null && StringUtils.hasText(generation.getStorageKey())) {
                    storageKeys.add(generation.getStorageKey());
                }
            }
        }
        if (StringUtils.hasText(asset.getImageStorageKey())) {
            storageKeys.add(asset.getImageStorageKey());
        }
        if (StringUtils.hasText(asset.getVoiceReferenceKey())) {
            storageKeys.add(asset.getVoiceReferenceKey());
        }
        forgetAssetCreations(generations);
        assetMapper.deleteById(asset.getId());
        // 删除资产后同步解除分镜参考绑定，保留分镜文字中的道具描述。
        storyboardService.detachAssetReference(asset.getEpisodeId(), asset.getId());
        touchSeries(asset.getSeriesId());
        List<String> releasedKeys = List.copyOf(storageKeys);
        afterCommit(() -> releasedKeys.forEach(characterReview::release));
        mediaRetention.purgeAfterCommit(asset.getUserId(), releasedKeys);
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
        DramaAsset baseSheet = findBaseCharacterSheet(asset);
        String prompt = baseSheet == null
                ? compileImagePromptForGenerate(asset, series)
                : DramaAssetDrafts.derivativePrompt(DramaAssetDrafts.derivativeChange(
                        asset.getName(), asset.getStateTag(), asset.getDescription(), asset.getIdentityInvariants()));
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
        if (baseSheet != null) {
            GenerateSubmitReq.ReferenceInput input = new GenerateSubmitReq.ReferenceInput();
            input.setType(AssetKind.IMAGE);
            if (StringUtils.hasText(baseSheet.getImageStorageKey())) {
                input.setStorageKey(baseSheet.getImageStorageKey());
            } else {
                input.setAssetId(baseSheet.getImageAssetId());
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
        String name = draft.getName().trim();
        String stateTag = StringUtils.hasText(draft.getStateTag())
                ? draft.getStateTag().trim()
                : DramaAssetDrafts.inferStateTag(name);
        String key = uniqueKey(type, name, stateTag);
        DramaAsset current = existing.get(key);
        if (current != null) {
            // 其他章节已经有同名资产时直接复用，不另插一行。
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
        return "生成方案 " + next;
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

    /** 新增时同一分类下名称已存在则拒绝，避免再插一条。提取剧本仍复用已有记录。 */
    private void rejectDuplicateName(Long seriesId, String type, String name) {
        if (seriesId == null || !StringUtils.hasText(name)) {
            return;
        }
        String wanted = name.trim();
        boolean exists = existingBySeries(seriesId).values().stream()
                .anyMatch(asset -> asset != null
                        && type.equals(asset.getType())
                        && wanted.equals(asset.getName() == null ? "" : asset.getName().trim()));
        if (exists) {
            throw new BizException(ErrorCode.BAD_REQUEST, duplicateAssetMessage(type));
        }
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
        String rebuilt = DramaAssetDrafts.buildPrompt(
                asset.getType(), asset.getName(), asset.getStateTag(), style, null, asset.getIdentityInvariants());
        rebuilt = withIdentityConstraint(rebuilt, asset.getType(), asset.getIdentityInvariants());
        if (StringUtils.hasText(rebuilt) && !rebuilt.equals(blankToNull(asset.getImagePrompt()))) {
            asset.setImagePrompt(rebuilt);
            scrubTemplateDescription(asset, style);
            assetMapper.updateById(asset);
        }
        return rebuilt;
    }

    /** 创建/更新外形后刷新完整提示词缓存，供侧栏只读预览。 */
    private void refreshCompiledImagePrompt(DramaAsset asset) {
        if (asset == null) {
            return;
        }
        DramaStyles.Spec style = DramaStyles.require(requireOwnedSeries(asset.getSeriesId()).getStyleKey());
        applyCompiledImagePrompt(asset, style);
    }

    private static void applyCompiledImagePrompt(DramaAsset asset, DramaStyles.Spec style) {
        String rebuilt = DramaAssetDrafts.buildPrompt(
                asset.getType(), asset.getName(), asset.getStateTag(), style, null, asset.getIdentityInvariants());
        rebuilt = withIdentityConstraint(rebuilt, asset.getType(), asset.getIdentityInvariants());
        asset.setImagePrompt(rebuilt);
        scrubTemplateDescription(asset, style);
    }

    /** description 若仍是旧版整段模板，降级为外形摘要，避免与 identity 双源长句。 */
    private static void scrubTemplateDescription(DramaAsset asset, DramaStyles.Spec style) {
        if (asset == null || style == null) {
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
     * 换装 / 入梦且本体已有设定图时，返回那张图，生图改为在原图上改。没有可用本体图时返回 null，仍走整张设定图。
     */
    private DramaAsset findBaseCharacterSheet(DramaAsset asset) {
        if (asset == null
                || !DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())
                || asset.getEpisodeId() == null) {
            return null;
        }
        String state = StringUtils.hasText(asset.getStateTag())
                ? asset.getStateTag().trim()
                : DramaAssetDrafts.inferStateTag(asset.getName());
        if (DramaContract.STATE_TAG_BASE.equals(state)) {
            return null;
        }
        String baseName = DramaAssetDrafts.baseCharacterName(asset.getName());
        if (!StringUtils.hasText(baseName)) {
            return null;
        }
        List<DramaAsset> mates = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(asset.getSeriesId() != null, DramaAsset::getSeriesId, asset.getSeriesId())
                .eq(asset.getSeriesId() == null, DramaAsset::getEpisodeId, asset.getEpisodeId())
                .eq(DramaAsset::getType, DramaContract.ASSET_TYPE_CHARACTER)
                .eq(DramaAsset::getName, baseName));
        for (DramaAsset mate : mates) {
            if (mate == null || mate.getId() != null && mate.getId().equals(asset.getId())) {
                continue;
            }
            String mateState = StringUtils.hasText(mate.getStateTag())
                    ? mate.getStateTag().trim()
                    : DramaContract.STATE_TAG_BASE;
            if (!DramaContract.STATE_TAG_BASE.equals(mateState)) {
                continue;
            }
            if (StringUtils.hasText(mate.getImageStorageKey()) || mate.getImageAssetId() != null) {
                return mate;
            }
        }
        return null;
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
        return withIdentityConstraint(prompt, DramaContract.ASSET_TYPE_CHARACTER, identity);
    }

    static String withIdentityConstraint(String prompt, String type, String identity) {
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
