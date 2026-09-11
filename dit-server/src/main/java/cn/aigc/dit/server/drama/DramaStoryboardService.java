package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaChapter;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaChapterMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.model.req.DramaShotSaveReq;
import cn.aigc.dit.server.drama.model.resp.DramaChapterResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotReferenceResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotResp;
import cn.aigc.dit.server.drama.model.resp.DramaStoryboardResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.service.GenerateService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DramaStoryboardService {

    private final DramaSeriesMapper seriesMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaAssetMapper assetMapper;
    private final DramaChapterMapper chapterMapper;
    private final DramaShotMapper shotMapper;
    private final AiModelMapper aiModelMapper;
    private final GenTaskMapper genTaskMapper;
    private final GenerateService generateService;
    private final ObjectProvider<DramaTextService> textService;

    @Transactional
    public DramaStoryboardResp getStoryboard(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaChapter> chapters = listChapters(episode.getId());
        List<DramaShot> shots = listShots(episode.getId());
        Map<Long, DramaAsset> assets = assetsById(episode.getId());
        String stylePrefix = stylePrefix(episode.getSeriesId());
        boolean dirty = false;
        for (DramaShot shot : shots) {
            if (refreshStale(shot, assets, stylePrefix)) {
                dirty = true;
            }
        }
        applySucceededQuietly(shots);
        if (dirty) {
            refreshChapterStatuses(chapters, shots);
        }
        return toBoard(episode, chapters, shots, assets);
    }

    @Transactional
    public DramaStoryboardResp draft(Long episodeId) {
        return draft(episodeId, true);
    }

    /**
     * 拆章节并生成分镜草稿。分析闭环可关闭 LLM，快速生成规则草稿。
     */
    @Transactional
    public DramaStoryboardResp draft(Long episodeId, boolean useLlm) {
        DramaEpisode episode = requireEpisode(episodeId);
        if (!DramaContract.STATUS_LOCKED.equals(episode.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先锁定分场剧本再拆章节");
        }
        ScriptDoc doc = ScriptDocs.fromMap(episode.getScript());
        if (doc == null || doc.getScenes() == null || doc.getScenes().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集没有可拆章的分场剧本");
        }
        List<DramaShot> existing = listShots(episode.getId());
        if (existing.stream().anyMatch(item -> DramaContract.STATUS_LOCKED.equals(item.getStatus()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已有确认分镜，锁定剧本变更过期后再重新拆镜");
        }
        replaceStoryboard(episode, doc, useLlm);
        return getStoryboard(episode.getId());
    }

    @Transactional
    public DramaShotResp updateShot(Long shotId, DramaShotSaveReq req) {
        DramaShot shot = requireShot(shotId);
        if (DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已确认分镜请先等资产/剧本过期后再改");
        }
        applySave(shot, req);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        boolean autoBind = req.getReferenceAssetIds() == null || req.getReferenceAssetIds().isEmpty();
        rebindAndCompile(shot, assets, stylePrefix(shot.getSeriesId()), false, autoBind);
        if (DramaContract.STATUS_EXPIRED.equals(shot.getStatus())
                || DramaContract.STATUS_NOT_STARTED.equals(shot.getStatus())) {
            shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        shotMapper.updateById(shot);
        touchSeries(shot.getSeriesId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, stylePrefix(shot.getSeriesId()), loadTask(shot.getGenTaskId()));
    }

    @Transactional
    public DramaShotResp compileShot(Long shotId) {
        DramaShot shot = requireShot(shotId);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        rebindAndCompile(shot, assets, stylePrefix(shot.getSeriesId()), false, true);
        if (DramaContract.STATUS_EXPIRED.equals(shot.getStatus())) {
            shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        shotMapper.updateById(shot);
        touchSeries(shot.getSeriesId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, stylePrefix(shot.getSeriesId()), loadTask(shot.getGenTaskId()));
    }

    @Transactional
    public DramaStoryboardResp compileEpisode(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        Map<Long, DramaAsset> assets = assetsById(episode.getId());
        String prefix = stylePrefix(episode.getSeriesId());
        for (DramaShot shot : listShots(episode.getId())) {
            if (DramaContract.STATUS_LOCKED.equals(shot.getStatus()) && !isStale(shot, assets, prefix)) {
                continue;
            }
            rebindAndCompile(shot, assets, prefix, false, true);
            if (DramaContract.STATUS_EXPIRED.equals(shot.getStatus())) {
                shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            }
            shotMapper.updateById(shot);
        }
        touchSeries(episode.getSeriesId());
        return getStoryboard(episode.getId());
    }

    @Transactional
    public DramaShotResp confirmShot(Long shotId) {
        DramaShot shot = requireShot(shotId);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefix(shot.getSeriesId());
        rebindAndCompile(shot, assets, prefix, false, true);
        List<String> unbound = unboundLockedRequired(shot, assets);
        if (!unbound.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未锁定不能进分镜参考：" + String.join("、", unbound));
        }
        if (!StringUtils.hasText(shot.getCompiledPrompt()) || isStale(shot, assets, prefix)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先编译分镜再确认");
        }
        shot.setStatus(DramaContract.STATUS_LOCKED);
        shotMapper.updateById(shot);
        refreshChapterStatuses(listChapters(shot.getEpisodeId()), listShots(shot.getEpisodeId()));
        touchSeries(shot.getSeriesId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, prefix, loadTask(shot.getGenTaskId()));
    }

    @Transactional
    public DramaShotGenerateResp generateShot(Long shotId) {
        DramaShot shot = requireShot(shotId);
        DramaSeries series = requireOwnedSeries(shot.getSeriesId());
        AiModel model = requireBoundVideoModel(series);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefixOf(series);
        rebindAndCompile(shot, assets, prefix, true, true);
        List<DramaAsset> refs = resolveReferences(shot, assets, true, true);
        if (refs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先锁定主体参考图再出片");
        }
        if (!StringUtils.hasText(shot.getCompiledPrompt())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先编译分镜再出片");
        }
        shotMapper.updateById(shot);

        if (shot.getGenTaskId() != null) {
            try {
                GenerateTaskResp existing = generateService.getMine(shot.getGenTaskId());
                if (isGeneratingStatus(existing.getStatus())) {
                    return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, existing);
                }
            } catch (BizException ignored) {
                // 旧任务已不可见：继续提交新任务
            }
        }

        assertGenerateCapacity(shot.getEpisodeId());
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(model.getId());
        req.setCategory(ModelCategory.VIDEO);
        req.setPrompt(shot.getCompiledPrompt());
        req.setRatio(series.getAspectRatio());
        req.setResolution(series.getDefaultResolution());
        req.setDurationSec(shot.getDurationSec() == null ? DramaContract.DEFAULT_DURATION_SEC : shot.getDurationSec());
        req.setCopies(1);
        List<GenerateSubmitReq.ReferenceInput> inputs = new ArrayList<>();
        for (DramaAsset asset : refs) {
            GenerateSubmitReq.ReferenceInput input = new GenerateSubmitReq.ReferenceInput();
            input.setType(AssetKind.IMAGE);
            if (StringUtils.hasText(asset.getImageStorageKey())) {
                input.setStorageKey(asset.getImageStorageKey());
            } else if (asset.getImageAssetId() != null) {
                input.setAssetId(asset.getImageAssetId());
            }
            inputs.add(input);
        }
        req.setReferences(inputs);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", GenerateRequests.SOURCE_DRAMA);
        extra.put("seriesId", series.getId());
        extra.put("episodeId", shot.getEpisodeId());
        extra.put("shotId", shot.getId());
        GenerateTaskResp task = generateService.submit(req, extra);
        shot.setGenTaskId(task.getId());
        shotMapper.updateById(shot);
        touchSeries(series.getId());
        return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, task);
    }

    @Transactional
    public DramaShotGenerateResp getGenerateTask(Long shotId, Long taskId) {
        DramaShot shot = requireShot(shotId);
        if (shot.getGenTaskId() == null || !shot.getGenTaskId().equals(taskId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "生成任务不存在");
        }
        GenerateTaskResp task = generateService.getMine(taskId);
        applyTaskResult(shot, task);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, stylePrefix(shot.getSeriesId()), task);
    }

    @Transactional
    public void expireByEpisode(Long episodeId) {
        if (episodeId == null) {
            return;
        }
        for (DramaChapter chapter : listChapters(episodeId)) {
            if (DramaContract.STATUS_EXPIRED.equals(chapter.getStatus())) {
                continue;
            }
            chapter.setStatus(DramaContract.STATUS_EXPIRED);
            chapterMapper.updateById(chapter);
        }
        for (DramaShot shot : listShots(episodeId)) {
            clearCompiled(shot);
            if (!DramaContract.STATUS_EXPIRED.equals(shot.getStatus())) {
                shot.setStatus(DramaContract.STATUS_EXPIRED);
            }
            shotMapper.updateById(shot);
        }
    }

    @Transactional
    public void expireCompiledBySeries(Long seriesId) {
        if (seriesId == null) {
            return;
        }
        List<DramaShot> shots = shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getSeriesId, seriesId));
        for (DramaShot shot : shots) {
            if (!StringUtils.hasText(shot.getCompiledPrompt())
                    && !DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
                continue;
            }
            clearCompiled(shot);
            if (DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
                shot.setStatus(DramaContract.STATUS_EXPIRED);
            }
            shotMapper.updateById(shot);
        }
    }

    @Transactional
    public void invalidateByAsset(Long episodeId, Long assetId) {
        if (episodeId == null || assetId == null) {
            return;
        }
        for (DramaShot shot : listShots(episodeId)) {
            List<Long> ids = idsOf(shot.getReferenceAssetIds());
            if (!ids.contains(assetId)) {
                continue;
            }
            clearCompiled(shot);
            if (DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
                shot.setStatus(DramaContract.STATUS_EXPIRED);
            } else if (!DramaContract.STATUS_EXPIRED.equals(shot.getStatus())) {
                shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            }
            shotMapper.updateById(shot);
        }
    }

    private void replaceStoryboard(DramaEpisode episode, ScriptDoc doc, boolean useLlm) {
        Long userId = UserContext.requireUserId();
        DramaSeries series = requireOwnedSeries(episode.getSeriesId());
        int duration = DramaService.normalizeDuration(series.getDefaultDurationSec());
        List<DramaAsset> assets = listAssets(episode.getId());
        List<String> assetNames = assets.stream()
                .map(DramaAsset::getName)
                .filter(StringUtils::hasText)
                .toList();
        ShotDraftList drafts = resolveDrafts(episode.getSeriesId(), doc, assetNames, duration, useLlm);

        for (DramaShot shot : listShots(episode.getId())) {
            shotMapper.deleteById(shot.getId());
        }
        for (DramaChapter chapter : listChapters(episode.getId())) {
            chapterMapper.deleteById(chapter.getId());
        }

        Map<Integer, ScriptDoc.Scene> scenes = scenesByIndex(doc);
        Map<Integer, DramaChapter> chapters = new LinkedHashMap<>();
        int sceneFallback = 0;
        for (ScriptDoc.Scene scene : doc.getScenes()) {
            if (scene == null) {
                continue;
            }
            sceneFallback++;
            int index = scene.getIndex() == null ? sceneFallback : scene.getIndex();
            DramaChapter chapter = new DramaChapter();
            chapter.setSeriesId(episode.getSeriesId());
            chapter.setEpisodeId(episode.getId());
            chapter.setUserId(userId);
            chapter.setChapterIndex(index);
            chapter.setSceneIndex(index);
            chapter.setName(overlayChapterName(drafts, scene, index));
            chapter.setBody(DramaShotDrafts.chapterBody(scene));
            chapter.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            chapterMapper.insert(chapter);
            chapters.put(index, chapter);
        }

        Map<Long, DramaAsset> byId = new HashMap<>();
        for (DramaAsset asset : assets) {
            byId.put(asset.getId(), asset);
        }
        String prefix = stylePrefixOf(series);
        int shotIndex = 0;
        Map<Integer, Integer> perChapter = new HashMap<>();
        for (ShotDraftList.ShotDraft draft : drafts.getShots() == null ? List.<ShotDraftList.ShotDraft>of() : drafts.getShots()) {
            if (draft == null) {
                continue;
            }
            Integer chapterIndex = draft.getChapterIndex();
            DramaChapter chapter = chapterIndex == null ? null : chapters.get(chapterIndex);
            if (chapter == null && !chapters.isEmpty()) {
                chapter = chapters.values().iterator().next();
                chapterIndex = chapter.getChapterIndex();
            }
            if (chapter == null) {
                continue;
            }
            int used = perChapter.getOrDefault(chapterIndex, 0);
            if (used >= DramaContract.MAX_SHOTS_PER_CHAPTER) {
                continue;
            }
            shotIndex++;
            perChapter.put(chapterIndex, used + 1);
            DramaShot shot = new DramaShot();
            shot.setSeriesId(episode.getSeriesId());
            shot.setEpisodeId(episode.getId());
            shot.setChapterId(chapter.getId());
            shot.setUserId(userId);
            shot.setShotIndex(shotIndex);
            shot.setTitle(StringUtils.hasText(draft.getTitle()) ? draft.getTitle().trim() : "分镜 " + shotIndex);
            shot.setDurationSec(DramaShotDrafts.normalizeDuration(draft.getDurationSec() == null ? duration : draft.getDurationSec()));
            shot.setNarrativeGoal(blankToNull(draft.getNarrativeGoal()));
            shot.setScene(blankToNull(draft.getScene()));
            shot.setDialogue(blankToNull(draft.getDialogue()));
            shot.setContinuityIn(blankToNull(draft.getContinuityIn()));
            shot.setContinuityOut(blankToNull(draft.getContinuityOut()));
            List<String> characters = cleanNames(draft.getCharacterNames());
            if (characters.isEmpty()) {
                ScriptDoc.Scene scene = scenes.get(chapterIndex);
                characters = scene == null ? List.of() : cleanNames(scene.getCharacters());
            }
            shot.setCharacterNames(characters);
            List<String> props = cleanNames(draft.getPropNames());
            shot.setPropNames(props);
            shot.setLocationName(StringUtils.hasText(draft.getLocationName())
                    ? draft.getLocationName().trim()
                    : (scenes.get(chapterIndex) == null ? null : scenes.get(chapterIndex).getLocation()));
            shot.setCastCount(characters.size());
            shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            shot.setReferenceAssetIds(new ArrayList<>());
            rebindAndCompile(shot, byId, prefix, false, true);
            shotMapper.insert(shot);
        }
        if (shotIndex == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未能生成分镜草稿");
        }
        touchSeries(episode.getSeriesId());
    }

    private ShotDraftList resolveDrafts(long seriesId, ScriptDoc doc, List<String> assetNames, int duration, boolean useLlm) {
        DramaTextService svc = textService.getIfAvailable();
        ShotDraftList drafts = null;
        if (useLlm && svc != null) {
            drafts = svc.draftShots(seriesId, doc, assetNames);
        }
        if (drafts == null || drafts.getShots() == null || drafts.getShots().isEmpty()) {
            drafts = DramaShotDrafts.fromScript(doc, assetNames, duration);
        }
        return drafts;
    }

    private void applySave(DramaShot shot, DramaShotSaveReq req) {
        if (req.getTitle() != null) {
            shot.setTitle(StringUtils.hasText(req.getTitle()) ? req.getTitle().trim() : shot.getTitle());
        }
        if (req.getDurationSec() != null) {
            shot.setDurationSec(DramaService.normalizeDuration(req.getDurationSec()));
        }
        if (req.getNarrativeGoal() != null) {
            shot.setNarrativeGoal(blankToNull(req.getNarrativeGoal()));
        }
        if (req.getScene() != null) {
            shot.setScene(blankToNull(req.getScene()));
        }
        if (req.getDialogue() != null) {
            shot.setDialogue(blankToNull(req.getDialogue()));
        }
        if (req.getContinuityIn() != null) {
            shot.setContinuityIn(blankToNull(req.getContinuityIn()));
        }
        if (req.getContinuityOut() != null) {
            shot.setContinuityOut(blankToNull(req.getContinuityOut()));
        }
        if (req.getCharacterNames() != null) {
            shot.setCharacterNames(cleanNames(req.getCharacterNames()));
        }
        if (req.getPropNames() != null) {
            shot.setPropNames(cleanNames(req.getPropNames()));
        }
        if (req.getLocationName() != null) {
            shot.setLocationName(blankToNull(req.getLocationName()));
        }
        shot.setCastCount(cleanNames(shot.getCharacterNames()).size());
        if (req.getReferenceAssetIds() != null) {
            shot.setReferenceAssetIds(new ArrayList<>(req.getReferenceAssetIds()));
        }
    }

    private void rebindAndCompile(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix,
                                 boolean requireLocked, boolean autoBindIfEmpty) {
        List<DramaAsset> ordered = resolveReferences(shot, assets, requireLocked, autoBindIfEmpty);
        shot.setReferenceAssetIds(ordered.stream().map(DramaAsset::getId).toList());
        shot.setCastCount(cleanNames(shot.getCharacterNames()).size());
        List<DramaStoryboardCompiler.ActiveReference> refs = new ArrayList<>();
        for (DramaAsset asset : ordered) {
            refs.add(toActive(asset));
        }
        DramaStoryboardCompiler.Result compiled = DramaStoryboardCompiler.compile(
                stylePrefix,
                refs,
                new DramaStoryboardCompiler.ShotContent(
                        shot.getScene(),
                        shot.getNarrativeGoal(),
                        shot.getDialogue(),
                        shot.getContinuityIn(),
                        shot.getContinuityOut(),
                        shot.getDurationSec() == null ? DramaContract.DEFAULT_DURATION_SEC : shot.getDurationSec(),
                        cleanNames(shot.getCharacterNames()),
                        shot.getCastCount() == null ? 0 : shot.getCastCount()));
        shot.setCompiledPrompt(compiled.prompt());
        shot.setCompiledFingerprint(compiled.fingerprint());
    }

    private List<DramaAsset> resolveReferences(
            DramaShot shot, Map<Long, DramaAsset> assets, boolean requireLocked, boolean autoBindIfEmpty) {
        boolean dream = isDreamContext(shot);
        LinkedHashMap<Long, DramaAsset> ordered = new LinkedHashMap<>();
        List<Long> explicit = idsOf(shot.getReferenceAssetIds());
        if (!autoBindIfEmpty) {
            for (Long id : explicit) {
                DramaAsset asset = assets.get(id);
                if (asset == null) {
                    continue;
                }
                if (!isReferenceEligible(asset)) {
                    if (requireLocked) {
                        throw new BizException(ErrorCode.BAD_REQUEST, asset.getName() + "尚未锁定，不能进入分镜参考");
                    }
                    continue;
                }
                ordered.put(asset.getId(), asset);
            }
            return new ArrayList<>(ordered.values());
        }
        for (String name : cleanNames(shot.getCharacterNames())) {
            DramaAsset asset = findAsset(assets, name, DramaContract.ASSET_TYPE_CHARACTER, dream);
            acceptRef(ordered, asset, requireLocked);
        }
        for (String name : cleanNames(shot.getPropNames())) {
            DramaAsset asset = findAsset(assets, name, DramaContract.ASSET_TYPE_PROP, false);
            acceptRef(ordered, asset, requireLocked);
        }
        if (StringUtils.hasText(shot.getLocationName())) {
            DramaAsset asset = findAsset(assets, shot.getLocationName(), DramaContract.ASSET_TYPE_SCENE, false);
            acceptRef(ordered, asset, requireLocked);
        }
        return new ArrayList<>(ordered.values());
    }

    private void acceptRef(Map<Long, DramaAsset> ordered, DramaAsset asset, boolean requireLocked) {
        if (asset == null) {
            return;
        }
        if (!isReferenceEligible(asset)) {
            if (requireLocked) {
                throw new BizException(ErrorCode.BAD_REQUEST, asset.getName() + "尚未锁定，不能进入分镜参考");
            }
            return;
        }
        ordered.putIfAbsent(asset.getId(), asset);
    }

    private DramaAsset findAsset(Map<Long, DramaAsset> assets, String name, String type, boolean preferDream) {
        DramaAsset dream = null;
        DramaAsset base = null;
        DramaAsset any = null;
        for (DramaAsset asset : assets.values()) {
            if (!type.equals(asset.getType()) || !nameEquals(asset.getName(), name)) {
                continue;
            }
            any = asset;
            if (DramaContract.STATE_TAG_DREAM.equals(asset.getStateTag())) {
                dream = asset;
            } else if (DramaContract.STATE_TAG_BASE.equals(asset.getStateTag()) || !StringUtils.hasText(asset.getStateTag())) {
                base = asset;
            }
        }
        if (preferDream && dream != null) {
            return dream;
        }
        if (base != null) {
            return base;
        }
        return dream != null ? dream : any;
    }

    private boolean nameEquals(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        return left.trim().equals(right.trim());
    }

    private boolean isDreamContext(DramaShot shot) {
        String blob = String.join(" ",
                String.valueOf(shot.getTitle()),
                String.valueOf(shot.getScene()),
                String.valueOf(shot.getNarrativeGoal()),
                String.join(" ", cleanNames(shot.getCharacterNames())));
        return blob.contains("入梦") || blob.contains("梦境") || blob.contains("梦魇");
    }

    private boolean refreshStale(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix) {
        if (!StringUtils.hasText(shot.getCompiledPrompt())) {
            return false;
        }
        if (!isStale(shot, assets, stylePrefix)) {
            return false;
        }
        clearCompiled(shot);
        if (DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
            shot.setStatus(DramaContract.STATUS_EXPIRED);
        }
        shotMapper.updateById(shot);
        return true;
    }

    private boolean isStale(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix) {
        if (!StringUtils.hasText(shot.getCompiledFingerprint())) {
            return StringUtils.hasText(shot.getCompiledPrompt());
        }
        List<DramaStoryboardCompiler.ActiveReference> refs = new ArrayList<>();
        for (Long id : idsOf(shot.getReferenceAssetIds())) {
            DramaAsset asset = assets.get(id);
            if (asset != null) {
                refs.add(toActive(asset));
            }
        }
        String current = DramaStoryboardCompiler.fingerprint(
                stylePrefix,
                refs,
                new DramaStoryboardCompiler.ShotContent(
                        shot.getScene(),
                        shot.getNarrativeGoal(),
                        shot.getDialogue(),
                        shot.getContinuityIn(),
                        shot.getContinuityOut(),
                        shot.getDurationSec() == null ? DramaContract.DEFAULT_DURATION_SEC : shot.getDurationSec(),
                        cleanNames(shot.getCharacterNames()),
                        shot.getCastCount() == null ? 0 : shot.getCastCount()));
        return !current.equals(shot.getCompiledFingerprint());
    }

    private List<String> unboundLockedRequired(DramaShot shot, Map<Long, DramaAsset> assets) {
        List<String> missing = new ArrayList<>();
        boolean dream = isDreamContext(shot);
        for (String name : cleanNames(shot.getCharacterNames())) {
            DramaAsset asset = findAsset(assets, name, DramaContract.ASSET_TYPE_CHARACTER, dream);
            if (asset != null && !isReferenceEligible(asset) && !missing.contains(asset.getName())) {
                missing.add(asset.getName());
            }
        }
        for (String name : cleanNames(shot.getPropNames())) {
            DramaAsset asset = findAsset(assets, name, DramaContract.ASSET_TYPE_PROP, false);
            if (asset != null && !isReferenceEligible(asset) && !missing.contains(asset.getName())) {
                missing.add(asset.getName());
            }
        }
        if (StringUtils.hasText(shot.getLocationName())) {
            DramaAsset asset = findAsset(assets, shot.getLocationName(), DramaContract.ASSET_TYPE_SCENE, false);
            if (asset != null && !isReferenceEligible(asset) && !missing.contains(asset.getName())) {
                missing.add(asset.getName());
            }
        }
        return missing;
    }

    private void refreshChapterStatuses(List<DramaChapter> chapters, List<DramaShot> shots) {
        Map<Long, List<DramaShot>> byChapter = new HashMap<>();
        for (DramaShot shot : shots) {
            byChapter.computeIfAbsent(shot.getChapterId(), key -> new ArrayList<>()).add(shot);
        }
        for (DramaChapter chapter : chapters) {
            List<DramaShot> group = byChapter.getOrDefault(chapter.getId(), List.of());
            String next;
            if (group.isEmpty()) {
                next = DramaContract.STATUS_PENDING_CONFIRM;
            } else if (group.stream().allMatch(item -> DramaContract.STATUS_LOCKED.equals(item.getStatus()))) {
                next = DramaContract.STATUS_LOCKED;
            } else if (group.stream().anyMatch(item -> DramaContract.STATUS_EXPIRED.equals(item.getStatus()))) {
                next = DramaContract.STATUS_EXPIRED;
            } else {
                next = DramaContract.STATUS_PENDING_CONFIRM;
            }
            if (!Objects.equals(next, chapter.getStatus())) {
                chapter.setStatus(next);
                chapterMapper.updateById(chapter);
            }
        }
    }

    private DramaStoryboardResp toBoard(
            DramaEpisode episode,
            List<DramaChapter> chapters,
            List<DramaShot> shots,
            Map<Long, DramaAsset> assets) {
        String prefix = stylePrefix(episode.getSeriesId());
        Map<Long, List<DramaShot>> byChapter = new LinkedHashMap<>();
        for (DramaShot shot : shots) {
            byChapter.computeIfAbsent(shot.getChapterId(), key -> new ArrayList<>()).add(shot);
        }
        List<DramaAsset> locked = assets.values().stream()
                .filter(DramaStoryboardService::isReferenceEligible)
                .sorted(Comparator.comparing(DramaAsset::getType).thenComparing(DramaAsset::getId))
                .toList();
        int stale = 0;
        Map<Long, GenTask> tasks = loadTasks(shots);
        List<DramaChapterResp> chapterResps = new ArrayList<>();
        for (DramaChapter chapter : chapters) {
            DramaChapterResp resp = toChapterResp(chapter);
            List<DramaShotResp> shotResps = new ArrayList<>();
            for (DramaShot shot : byChapter.getOrDefault(chapter.getId(), List.of())) {
                DramaShotResp item = toShotResp(shot, chapter, assets, prefix,
                        shot.getGenTaskId() == null ? null : tasks.get(shot.getGenTaskId()));
                if (Boolean.TRUE.equals(item.getCompiledStale())) {
                    stale++;
                }
                shotResps.add(item);
            }
            resp.setShots(shotResps);
            chapterResps.add(resp);
        }
        DramaStoryboardResp board = new DramaStoryboardResp();
        board.setEpisodeId(episode.getId());
        board.setSeriesId(episode.getSeriesId());
        board.setStatus(boardStatus(chapterResps));
        board.setCanDraft(DramaContract.STATUS_LOCKED.equals(episode.getStatus()));
        board.setTextServiceAvailable(textService.getIfAvailable() != null);
        board.setLockedAssetCount(locked.size());
        board.setStaleShotCount(stale);
        board.setChapters(chapterResps);
        board.setLockedAssets(locked.stream().map(this::toLockedAsset).toList());
        return board;
    }

    private static String boardStatus(List<DramaChapterResp> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return DramaContract.STATUS_NOT_STARTED;
        }
        boolean allLocked = true;
        boolean anyExpired = false;
        for (DramaChapterResp chapter : chapters) {
            if (!DramaContract.STATUS_LOCKED.equals(chapter.getStatus())) {
                allLocked = false;
            }
            if (DramaContract.STATUS_EXPIRED.equals(chapter.getStatus())) {
                anyExpired = true;
            }
        }
        if (allLocked) {
            return DramaContract.STATUS_LOCKED;
        }
        if (anyExpired) {
            return DramaContract.STATUS_EXPIRED;
        }
        return DramaContract.STATUS_PENDING_CONFIRM;
    }

    private DramaChapterResp toChapterResp(DramaChapter chapter) {
        DramaChapterResp resp = new DramaChapterResp();
        resp.setId(chapter.getId());
        resp.setSeriesId(chapter.getSeriesId());
        resp.setEpisodeId(chapter.getEpisodeId());
        resp.setChapterIndex(chapter.getChapterIndex());
        resp.setName(chapter.getName());
        resp.setSceneIndex(chapter.getSceneIndex());
        resp.setBody(chapter.getBody());
        resp.setStatus(chapter.getStatus());
        resp.setCreatedAt(chapter.getCreatedAt());
        resp.setUpdatedAt(chapter.getUpdatedAt());
        return resp;
    }

    private DramaShotResp toShotResp(
            DramaShot shot, DramaChapter chapter, Map<Long, DramaAsset> assets, String stylePrefix, GenTask task) {
        DramaShotResp resp = new DramaShotResp();
        resp.setId(shot.getId());
        resp.setSeriesId(shot.getSeriesId());
        resp.setEpisodeId(shot.getEpisodeId());
        resp.setChapterId(shot.getChapterId());
        if (chapter != null) {
            resp.setChapterIndex(chapter.getChapterIndex());
            resp.setChapterName(chapter.getName());
        }
        resp.setShotIndex(shot.getShotIndex());
        resp.setTitle(shot.getTitle());
        resp.setDurationSec(shot.getDurationSec());
        resp.setNarrativeGoal(shot.getNarrativeGoal());
        resp.setScene(shot.getScene());
        resp.setDialogue(shot.getDialogue());
        resp.setContinuityIn(shot.getContinuityIn());
        resp.setContinuityOut(shot.getContinuityOut());
        List<DramaAsset> bound = resolveReferences(shot, assets, false, true);
        List<Long> ids = bound.stream().map(DramaAsset::getId).toList();
        resp.setReferenceAssetIds(ids);
        List<DramaShotReferenceResp> refs = new ArrayList<>();
        int picture = 1;
        for (DramaAsset asset : bound) {
            DramaShotReferenceResp ref = new DramaShotReferenceResp();
            ref.setPictureIndex(picture++);
            ref.setAssetId(asset.getId());
            ref.setName(asset.getName());
            ref.setType(asset.getType());
            ref.setStateTag(asset.getStateTag());
            ref.setIdentityVersion(asset.getIdentityVersion() == null ? 0 : asset.getIdentityVersion());
            ref.setImageUrl(MediaUrls.of(asset.getImageStorageKey()));
            ref.setLocked(isReferenceEligible(asset));
            refs.add(ref);
        }
        resp.setReferences(refs);
        resp.setCharacterNames(cleanNames(shot.getCharacterNames()));
        resp.setPropNames(cleanNames(shot.getPropNames()));
        resp.setLocationName(shot.getLocationName());
        resp.setCastCount(shot.getCastCount() == null ? resp.getCharacterNames().size() : shot.getCastCount());
        resp.setCompiledPrompt(shot.getCompiledPrompt());
        resp.setCompiledFingerprint(shot.getCompiledFingerprint());
        resp.setCompiledStale(isStale(shot, assets, stylePrefix));
        resp.setUnboundNames(unboundLockedRequired(shot, assets));
        resp.setStatus(shot.getStatus());
        resp.setGenTaskId(shot.getGenTaskId());
        resp.setVideoKey(shot.getVideoKey());
        resp.setVideoUrl(MediaUrls.of(shot.getVideoKey()));
        resp.setVideoPosterKey(shot.getVideoPosterKey());
        resp.setVideoPosterUrl(MediaUrls.of(shot.getVideoPosterKey()));
        if (task != null) {
            resp.setGenTaskStatus(task.getStatus());
            resp.setGenErrorMessage(task.getErrorMessage());
            resp.setGenerating(isGeneratingStatus(task.getStatus()));
        } else {
            resp.setGenerating(false);
        }
        resp.setCreatedAt(shot.getCreatedAt());
        resp.setUpdatedAt(shot.getUpdatedAt());
        return resp;
    }

    private DramaShotGenerateResp toGenerateResp(
            DramaShot shot, DramaChapter chapter, Map<Long, DramaAsset> assets, String prefix, GenerateTaskResp task) {
        DramaShotGenerateResp resp = new DramaShotGenerateResp();
        GenTask snapshot = new GenTask();
        if (task != null) {
            snapshot.setStatus(task.getStatus());
            snapshot.setErrorMessage(task.getErrorMessage());
        }
        resp.setShot(toShotResp(shot, chapter, assets, prefix, task == null ? null : snapshot));
        if (resp.getShot() != null && task != null) {
            resp.getShot().setGenerating(isGeneratingStatus(task.getStatus()));
            resp.getShot().setGenTaskStatus(task.getStatus());
            resp.getShot().setGenErrorMessage(task.getErrorMessage());
            resp.getShot().setGenTaskId(shot.getGenTaskId());
        }
        resp.setTask(task);
        return resp;
    }

    private void applySucceededQuietly(List<DramaShot> shots) {
        for (DramaShot shot : shots) {
            if (shot.getGenTaskId() == null) {
                continue;
            }
            try {
                GenerateTaskResp task = generateService.getMine(shot.getGenTaskId());
                applyTaskResult(shot, task);
            } catch (BizException ignored) {
                // 任务不属于当前用户或已删：列表仍返回分镜本身
            }
        }
    }

    private void applyTaskResult(DramaShot shot, GenerateTaskResp task) {
        if (task == null || !TaskStatus.SUCCEEDED.equals(task.getStatus())) {
            return;
        }
        String key = firstOutputKey(task);
        String poster = blankToNull(task.getPosterKey());
        boolean videoChanged = StringUtils.hasText(key) && !key.equals(shot.getVideoKey());
        boolean posterChanged = StringUtils.hasText(poster) && !poster.equals(shot.getVideoPosterKey());
        if (!videoChanged && !posterChanged) {
            return;
        }
        if (videoChanged) {
            shot.setVideoKey(key);
            shot.setVideoPosterKey(poster);
        } else if (posterChanged) {
            shot.setVideoPosterKey(poster);
        }
        shotMapper.updateById(shot);
    }

    private void assertGenerateCapacity(Long episodeId) {
        Long running = genTaskMapper.selectCount(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getUserId, UserContext.requireUserId())
                .in(GenTask::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING)
                .apply("params->>'source' = {0}", GenerateRequests.SOURCE_DRAMA)
                .apply("params->>'episodeId' = {0}", String.valueOf(episodeId))
                .apply("params->>'shotId' is not null"));
        if (running != null && running >= DramaContract.MAX_SHOT_GENERATE) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "同时出片不能超过 " + DramaContract.MAX_SHOT_GENERATE + " 条，请稍后再试");
        }
    }

    private AiModel requireBoundVideoModel(DramaSeries series) {
        if (series.getVideoModelId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先在分镜页选择视频模型");
        }
        AiModel model = aiModelMapper.selectById(series.getVideoModelId());
        if (model == null || !ModelCategory.VIDEO.equalsIgnoreCase(model.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目绑定的视频模型不存在");
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目绑定的视频模型已停用，请在分镜页更换");
        }
        if (!GenerateRequests.supportsMode(model.getCapabilities(), ModelCategory.VIDEO, GenerateMode.R2V)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前视频模型不支持参考生视频");
        }
        return model;
    }

    private Map<Long, GenTask> loadTasks(List<DramaShot> shots) {
        Set<Long> ids = new HashSet<>();
        for (DramaShot shot : shots) {
            if (shot.getGenTaskId() != null) {
                ids.add(shot.getGenTaskId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
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

    static boolean isGeneratingStatus(String status) {
        return TaskStatus.PENDING.equals(status) || TaskStatus.RUNNING.equals(status);
    }

    private cn.aigc.dit.server.drama.model.resp.DramaAssetResp toLockedAsset(DramaAsset asset) {
        cn.aigc.dit.server.drama.model.resp.DramaAssetResp resp =
                new cn.aigc.dit.server.drama.model.resp.DramaAssetResp();
        resp.setId(asset.getId());
        resp.setSeriesId(asset.getSeriesId());
        resp.setEpisodeId(asset.getEpisodeId());
        resp.setName(asset.getName());
        resp.setType(asset.getType());
        resp.setStateTag(asset.getStateTag());
        resp.setStatus(asset.getStatus());
        resp.setIdentityVersion(asset.getIdentityVersion() == null ? 0 : asset.getIdentityVersion());
        resp.setImageStorageKey(asset.getImageStorageKey());
        resp.setImageAssetId(asset.getImageAssetId());
        resp.setImageUrl(MediaUrls.of(asset.getImageStorageKey()));
        resp.setReferenceEligible(true);
        return resp;
    }

    private static DramaStoryboardCompiler.ActiveReference toActive(DramaAsset asset) {
        String descriptor = StringUtils.hasText(asset.getDescription())
                ? asset.getDescription()
                : asset.getImagePrompt();
        return new DramaStoryboardCompiler.ActiveReference(
                asset.getId(),
                asset.getIdentityVersion() == null ? 0 : asset.getIdentityVersion(),
                asset.getName(),
                asset.getType(),
                descriptor,
                asset.getIdentityInvariants());
    }

    private static void clearCompiled(DramaShot shot) {
        shot.setCompiledPrompt(null);
        shot.setCompiledFingerprint(null);
    }

    private static boolean isReferenceEligible(DramaAsset asset) {
        return asset != null
                && DramaContract.STATUS_LOCKED.equals(asset.getStatus())
                && StringUtils.hasText(asset.getImageStorageKey());
    }

    private String overlayChapterName(ShotDraftList drafts, ScriptDoc.Scene scene, int index) {
        if (drafts.getChapters() != null) {
            for (ShotDraftList.ChapterDraft chapter : drafts.getChapters()) {
                if (chapter != null && Objects.equals(index, chapter.getIndex()) && StringUtils.hasText(chapter.getName())) {
                    return chapter.getName().trim();
                }
            }
        }
        return DramaShotDrafts.chapterName(scene, index);
    }

    private static Map<Integer, ScriptDoc.Scene> scenesByIndex(ScriptDoc doc) {
        Map<Integer, ScriptDoc.Scene> map = new LinkedHashMap<>();
        int fallback = 0;
        for (ScriptDoc.Scene scene : doc.getScenes()) {
            if (scene == null) {
                continue;
            }
            fallback++;
            int index = scene.getIndex() == null ? fallback : scene.getIndex();
            map.put(index, scene);
        }
        return map;
    }

    private List<DramaChapter> listChapters(Long episodeId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<DramaChapter>()
                .eq(DramaChapter::getEpisodeId, episodeId)
                .orderByAsc(DramaChapter::getChapterIndex));
    }

    private List<DramaShot> listShots(Long episodeId) {
        return shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getEpisodeId, episodeId)
                .orderByAsc(DramaShot::getShotIndex));
    }

    private List<DramaAsset> listAssets(Long episodeId) {
        return assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episodeId)
                .orderByAsc(DramaAsset::getId));
    }

    private Map<Long, DramaAsset> assetsById(Long episodeId) {
        Map<Long, DramaAsset> map = new LinkedHashMap<>();
        for (DramaAsset asset : listAssets(episodeId)) {
            map.put(asset.getId(), asset);
        }
        return map;
    }

    private DramaChapter chapterOf(Long chapterId) {
        return chapterId == null ? null : chapterMapper.selectById(chapterId);
    }

    private String stylePrefix(Long seriesId) {
        return stylePrefixOf(requireOwnedSeries(seriesId));
    }

    private static String stylePrefixOf(DramaSeries series) {
        if (series != null && StringUtils.hasText(series.getStylePrefix())) {
            return series.getStylePrefix();
        }
        return DramaStyles.require(series == null ? DramaStyles.ANIME_COMIC : series.getStyleKey()).prefix();
    }

    private DramaEpisode requireEpisode(Long id) {
        DramaEpisode episode = episodeMapper.selectById(id);
        if (episode == null || !episode.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        return episode;
    }

    private DramaShot requireShot(Long id) {
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null || !shot.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "分镜不存在");
        }
        return shot;
    }

    private DramaSeries requireOwnedSeries(Long id) {
        DramaSeries series = seriesMapper.selectById(id);
        if (series == null || !series.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        return series;
    }

    private void touchSeries(Long seriesId) {
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series != null) {
            seriesMapper.updateById(series);
        }
    }

    static List<Long> idsOf(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Number number) {
                ids.add(number.longValue());
            }
        }
        return ids;
    }

    static List<String> cleanNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> cleaned = new ArrayList<>();
        for (String name : names) {
            if (StringUtils.hasText(name) && !cleaned.contains(name.trim())) {
                cleaned.add(name.trim());
            }
        }
        return cleaned;
    }

    static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
