package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.AudioReferenceCapabilities;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.poster.PosterExtractor;
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
import cn.aigc.dit.server.drama.model.req.DramaShotCreateReq;
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
import cn.aigc.dit.server.generate.GenerateTaskViews;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.service.AssetService;
import cn.aigc.dit.server.service.GenerateService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

@Slf4j
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
    private final AssetService assetService;
    private final PosterExtractor posterExtractor;
    private final ObjectProvider<DramaTextService> textService;
    private final DramaFilmService filmService;
    private final DramaStoryboardDraftLock draftLock;
    private final DramaPromptRecompileLock recompileLock;
    @Autowired
    private ObjectProvider<DramaStoryboardService> self;
    @Autowired(required = false)
    @Qualifier("storyboardDraftExecutor")
    private Executor storyboardDraftExecutor;

    @Transactional
    public DramaStoryboardResp getStoryboard(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        List<DramaChapter> chapters = listChapters(episode.getId());
        List<DramaShot> shots = listShots(episode.getId());
        Map<Long, DramaAsset> assets = assetsById(episode.getId());
        String stylePrefix = stylePrefix(episode.getSeriesId());
        DramaSeries series = requireOwnedSeries(episode.getSeriesId());
        boolean dirty = false;
        boolean recompiling = recompileLock.isActive(episode.getSeriesId());
        if (!recompiling) {
            for (DramaShot shot : shots) {
                if (refreshStale(shot, assets, stylePrefix, series)) {
                    dirty = true;
                }
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
        return draft(episodeId, true, null);
    }

    /**
     * 拆章节并生成分镜草稿。分析闭环可关闭 LLM，快速生成规则草稿。
     */
    @Transactional
    public DramaStoryboardResp draft(Long episodeId, boolean useLlm) {
        return draft(episodeId, useLlm, null);
    }

    @Transactional
    public DramaStoryboardResp draft(Long episodeId, boolean useLlm, Integer durationSec) {
        return draft(episodeId, useLlm, durationSec, null);
    }

    @Transactional
    public DramaStoryboardResp draft(
            Long episodeId, boolean useLlm, Integer durationSec, Integer episodeBudgetSec) {
        assertCanDraft(episodeId);
        draftLock.claim(episodeId);
        boolean held = true;
        try {
            DramaStoryboardResp board = finishDraft(episodeId, useLlm, durationSec, episodeBudgetSec);
            held = false;
            return board;
        } finally {
            if (held) {
                draftLock.release(episodeId);
            }
        }
    }

    /**
     * 分析结束后立刻占锁，让分镜页能显示「正在自动拆镜中」，真正的 LLM 拆镜在后台跑。
     */
    public void enqueueAutoDraft(Long episodeId, Integer episodeBudgetSec) {
        log.info("[drama] 自动分镜排队 episodeId={} episodeBudgetSec={}", episodeId, episodeBudgetSec);
        LoginUser user = UserContext.require();
        try {
            draftLock.claim(episodeId);
        } catch (BizException ex) {
            if (DramaStoryboardDraftLock.BUSY_MESSAGE.equals(ex.getMessage())) {
                log.info("自动拆镜已在进行 episodeId={}", episodeId);
                return;
            }
            throw ex;
        }
        draftExecutor().execute(() -> {
            LoginUser previous = UserContext.get();
            UserContext.set(user);
            try {
                log.info("[drama] 自动分镜开始 episodeId={}", episodeId);
                ops().runClaimedDraft(episodeId, null, episodeBudgetSec);
                log.info("[drama] 自动分镜完成 episodeId={}", episodeId);
            } catch (Exception e) {
                log.warn("自动拆镜失败 episodeId={}: {}", episodeId, e.toString());
                try {
                    draftLock.release(episodeId);
                } catch (RuntimeException ignored) {
                    log.warn("自动拆镜释放锁失败 episodeId={}", episodeId);
                }
            } finally {
                if (previous != null) {
                    UserContext.set(previous);
                } else {
                    UserContext.clear();
                }
            }
        });
    }

    public boolean isSeriesRecompiling(Long seriesId) {
        return recompileLock.isActive(seriesId);
    }

    /**
     * 立刻占锁让分镜页显示「正在重新编译」，真正编译放到事务提交后的后台线程。
     */
    public void enqueueSeriesRecompile(Long seriesId) {
        if (seriesId == null) {
            return;
        }
        int epoch = recompileLock.begin(seriesId);
        LoginUser user = UserContext.get();
        Runnable job = () -> {
            LoginUser previous = UserContext.get();
            if (user != null) {
                UserContext.set(user);
            }
            try {
                ops().recompileSeriesKeepingOverrides(seriesId);
            } catch (Exception e) {
                log.warn("recompile series failed seriesId={}: {}", seriesId, e.toString());
            } finally {
                recompileLock.end(seriesId, epoch);
                if (previous != null) {
                    UserContext.set(previous);
                } else {
                    UserContext.clear();
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    draftExecutor().execute(job);
                }
            });
        } else {
            draftExecutor().execute(job);
        }
    }

    /** 锁已占用，供后台线程经 Spring 代理提交事务。 */
    @Transactional
    public DramaStoryboardResp runClaimedDraft(
            Long episodeId, Integer durationSec, Integer episodeBudgetSec) {
        assertCanDraft(episodeId);
        boolean held = true;
        try {
            DramaStoryboardResp board = finishDraft(episodeId, true, durationSec, episodeBudgetSec);
            held = false;
            return board;
        } finally {
            if (held) {
                draftLock.release(episodeId);
            }
        }
    }

    private void assertCanDraft(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        if (!DramaContract.STATUS_LOCKED.equals(episode.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先锁定分场剧本再拆章节");
        }
        ScriptDoc doc = ScriptDocs.fromMap(episode.getScript());
        if (doc == null || doc.getScenes() == null || doc.getScenes().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前剧集没有可拆章的分场剧本");
        }
        if (listShots(episode.getId()).stream().anyMatch(item -> DramaContract.STATUS_LOCKED.equals(item.getStatus()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已有确认分镜，锁定剧本变更过期后再重新拆镜");
        }
    }

    private DramaStoryboardResp finishDraft(
            Long episodeId, boolean useLlm, Integer durationSec, Integer episodeBudgetSec) {
        DramaEpisode episode = requireEpisode(episodeId);
        ScriptDoc doc = ScriptDocs.fromMap(episode.getScript());
        replaceStoryboard(episode, doc, useLlm, durationSec, episodeBudgetSec);
        DramaStoryboardResp board = getStoryboard(episode.getId());
        board.setDrafting(false);
        releaseDraftLockWhenDone(episode.getId());
        return board;
    }

    private DramaStoryboardService ops() {
        DramaStoryboardService bean = self == null ? null : self.getIfAvailable();
        return bean != null ? bean : this;
    }

    private Executor draftExecutor() {
        return storyboardDraftExecutor != null ? storyboardDraftExecutor : Runnable::run;
    }

    private void releaseDraftLockWhenDone(Long episodeId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            draftLock.release(episodeId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                draftLock.release(episodeId);
            }
        });
    }

    @Transactional
    public DramaShotResp updateShot(Long shotId, DramaShotSaveReq req) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        if (DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已确认分镜请先等资产/剧本过期后再改");
        }
        applySave(shot, req);
        if (req.getVideoGenerateMode() != null
                && DramaVideoGenerateModes.isI2v(shot.getVideoGenerateMode())) {
            requireBoundVideoModel(requireOwnedSeries(shot.getSeriesId()), DramaVideoGenerateModes.I2V);
        }
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefix(shot.getSeriesId());
        boolean autoBind = req.getReferenceAssetIds() == null || req.getReferenceAssetIds().isEmpty();

        boolean overrideActive = applyPromptOverride(shot, req);
        if (overrideActive) {
            rebindReferencesOnly(shot, assets, false, autoBind);
        } else {
            rebindAndCompile(shot, assets, prefix, false, autoBind);
        }

        if (DramaContract.STATUS_EXPIRED.equals(shot.getStatus())
                || DramaContract.STATUS_NOT_STARTED.equals(shot.getStatus())) {
            shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        shotMapper.updateById(shot);
        touchSeries(shot.getSeriesId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, prefix, loadTask(shot.getGenTaskId()));
    }

    @Transactional
    public DramaShotResp compileShot(Long shotId) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        shot.setPromptOverride(null);
        rebindAndCompile(shot, assets, stylePrefix(shot.getSeriesId()), false, true, requireOwnedSeries(shot.getSeriesId()));
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
        requireNotDrafting(episode);
        requireNotRecompiling(episode);
        Map<Long, DramaAsset> assets = assetsById(episode.getId());
        String prefix = stylePrefix(episode.getSeriesId());
        DramaSeries series = requireOwnedSeries(episode.getSeriesId());
        for (DramaShot shot : listShots(episode.getId())) {
            if (DramaContract.STATUS_LOCKED.equals(shot.getStatus()) && !isStale(shot, assets, prefix, series)) {
                continue;
            }
            shot.setPromptOverride(null);
            rebindAndCompile(shot, assets, prefix, false, true, series);
            if (DramaContract.STATUS_EXPIRED.equals(shot.getStatus())) {
                shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            }
            shotMapper.updateById(shot);
        }
        touchSeries(episode.getSeriesId());
        return getStoryboard(episode.getId());
    }

    @Transactional
    public void recompileSeriesKeepingOverrides(Long seriesId) {
        if (seriesId == null) {
            return;
        }
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series == null) {
            return;
        }
        String prefix = stylePrefixOf(series);
        List<DramaEpisode> episodes = episodeMapper.selectList(new LambdaQueryWrapper<DramaEpisode>()
                .eq(DramaEpisode::getSeriesId, seriesId)
                .orderByAsc(DramaEpisode::getEpisodeNumber));
        for (DramaEpisode episode : episodes) {
            if (draftLock.isActive(episode)) {
                continue;
            }
            Map<Long, DramaAsset> assets = assetsById(episode.getId());
            for (DramaShot shot : listShots(episode.getId())) {
                if (StringUtils.hasText(shot.getPromptOverride())) {
                    continue;
                }
                if (!DramaVideoGenerateModes.inheritsMode(shot)) {
                    continue;
                }
                rebindAndCompile(shot, assets, prefix, false, true, series);
                shotMapper.updateById(shot);
            }
        }
        touchSeries(seriesId);
    }

    @Transactional
    public DramaShotResp uploadFirstFrame(Long shotId, MultipartFile file) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择首帧图片");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!mime.startsWith("image/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "首帧仅支持图片");
        }
        String key = assetService.putOwnedFile(file, "drama/" + shot.getSeriesId() + "/frames");
        shot.setFirstFrameKey(key);
        shot.setFirstFrameOrigin("upload");
        shotMapper.updateById(shot);
        touchSeries(shot.getSeriesId());
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, stylePrefix(shot.getSeriesId()), loadTask(shot.getGenTaskId()));
    }

    @Transactional
    public DramaShotResp clearFirstFrame(Long shotId) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        shot.setFirstFrameKey(null);
        shot.setFirstFrameOrigin(null);
        shotMapper.updateById(shot);
        touchSeries(shot.getSeriesId());
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, stylePrefix(shot.getSeriesId()), loadTask(shot.getGenTaskId()));
    }

    @Transactional
    public DramaShotResp usePrevTail(Long shotId) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        DramaShot prev = previousSameChapterShot(shot);
        if (prev == null) {
            DramaShot episodePrev = previousEpisodeShot(shot);
            if (episodePrev != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "上一镜不在同一场，不能接落幅");
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "场内第一镜没有上一镜落幅");
        }
        if (isShotInFlight(prev)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请等上一镜出片完成后再接落幅");
        }
        if (!StringUtils.hasText(prev.getVideoKey()) && !StringUtils.hasText(prev.getVideoTailKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上一镜还没有成片，无法接落幅");
        }
        String tail = ensureVideoTail(prev);
        if (!StringUtils.hasText(tail)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上一镜落幅尚未抽出，请稍后再试");
        }
        shot.setFirstFrameKey(tail);
        shot.setFirstFrameOrigin("prev-tail");
        shotMapper.updateById(shot);
        touchSeries(shot.getSeriesId());
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, stylePrefix(shot.getSeriesId()), loadTask(shot.getGenTaskId()));
    }

    @Transactional
    public DramaShotGenerateResp generateOpeningStill(Long shotId) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        DramaSeries series = requireOwnedSeries(shot.getSeriesId());
        if (!compileOptionsOf(series, shot).i2v()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "换开场静帧仅用于首尾帧模式");
        }
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefixOf(series);
        List<DramaAsset> refs = resolveReferences(shot, assets, true, true);
        return startOpeningStill(shot, series, assets, prefix, refs, false);
    }

    /**
     * 手工补一镜。可插到指定镜之后（胶片条中间的 +），否则挂到指定章节末尾。
     * 人物 / 道具 / 地点沿用前一镜，避免新镜一进来就是空镜编不出 prompt。
     */
    @Transactional
    public DramaShotResp createShot(Long episodeId, DramaShotCreateReq req) {
        DramaEpisode episode = requireEpisode(episodeId);
        requireNotDrafting(episode);
        requireNotRecompiling(episode);
        DramaSeries series = requireOwnedSeries(episode.getSeriesId());
        List<DramaChapter> chapters = listChapters(episodeId);
        if (chapters.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先生成分镜草稿再增删分镜");
        }
        List<DramaShot> shots = listShots(episodeId);
        DramaShot after = resolveAfterShot(episodeId, req);
        DramaChapter chapter = after != null
                ? chapterOf(after.getChapterId())
                : pickChapter(chapters, req == null ? null : req.getChapterId());
        if (chapter == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "章节不属于本剧集");
        }
        List<DramaShot> siblings = shots.stream()
                .filter(item -> chapter.getId().equals(item.getChapterId()))
                .toList();
        if (siblings.size() >= DramaContract.MAX_SHOTS_PER_CHAPTER) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单场最多 " + DramaContract.MAX_SHOTS_PER_CHAPTER + " 镜");
        }
        DramaShot template = after != null
                ? after
                : (siblings.isEmpty() ? null : siblings.get(siblings.size() - 1));
        int index = after != null
                ? after.getShotIndex() + 1
                : shots.stream().mapToInt(item -> item.getShotIndex() == null ? 0 : item.getShotIndex()).max().orElse(0) + 1;
        shiftShotIndexes(shots, index, 1);

        DramaShot shot = new DramaShot();
        shot.setSeriesId(episode.getSeriesId());
        shot.setEpisodeId(episodeId);
        shot.setChapterId(chapter.getId());
        shot.setUserId(UserContext.requireUserId());
        shot.setShotIndex(index);
        shot.setTitle(req != null && StringUtils.hasText(req.getTitle()) ? req.getTitle().trim() : "分镜 " + index);
        shot.setDurationSec(DramaService.normalizeDuration(series.getDefaultDurationSec()));
        shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        shot.setReferenceAssetIds(new ArrayList<>());
        shot.setCharacterNames(template == null || template.getCharacterNames() == null
                ? new ArrayList<>() : new ArrayList<>(template.getCharacterNames()));
        shot.setPropNames(template == null || template.getPropNames() == null
                ? new ArrayList<>() : new ArrayList<>(template.getPropNames()));
        shot.setLocationName(template == null ? null : template.getLocationName());
        shot.setCastCount(shot.getCharacterNames().size());
        DramaVideoGenerateModes.applyProductDefault(shot);

        Map<Long, DramaAsset> assets = assetsById(episodeId);
        String prefix = stylePrefix(episode.getSeriesId());
        rebindAndCompile(shot, assets, prefix, false, true);
        shotMapper.insert(shot);
        refreshChapterStatuses(chapters, listShots(episodeId));
        touchSeries(episode.getSeriesId());
        return toShotResp(shot, chapter, assets, prefix, null);
    }

    /**
     * 删除分镜。生成中 / 已确认 / 全集最后一镜拒绝。
     * 已出片可以删：本集若已拼接成片则删除成片文件并清空记录，需重新拼接。
     */
    @Transactional
    public DramaStoryboardResp deleteShot(Long shotId) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        GenTask task = loadTask(shot.getGenTaskId());
        GenTask live = loadInFlightDramaTasks(List.of(shot)).get(shot.getId());
        if (isActiveGenerateTask(task) || isActiveGenerateTask(live)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该镜正在生成，请等任务结束再删除");
        }
        if (DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已确认分镜不能删除");
        }
        List<DramaShot> all = listShots(shot.getEpisodeId());
        if (all.size() <= 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少保留一个分镜");
        }
        Long episodeId = shot.getEpisodeId();
        Long seriesId = shot.getSeriesId();
        shotMapper.deleteById(shotId);

        List<DramaShot> rest = listShots(episodeId).stream()
                .filter(item -> !shotId.equals(item.getId()))
                .toList();
        for (int i = 0; i < rest.size(); i++) {
            DramaShot item = rest.get(i);
            if (item.getShotIndex() == null || item.getShotIndex() != i + 1) {
                item.setShotIndex(i + 1);
                shotMapper.updateById(item);
            }
        }
        filmService.discardAssembledFilm(episodeId);
        refreshChapterStatuses(listChapters(episodeId), listShots(episodeId));
        touchSeries(seriesId);
        return getStoryboard(episodeId);
    }

    private DramaShot resolveAfterShot(Long episodeId, DramaShotCreateReq req) {
        if (req == null || req.getAfterShotId() == null) {
            return null;
        }
        DramaShot after = requireShot(req.getAfterShotId());
        if (!episodeId.equals(after.getEpisodeId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "分镜不属于本剧集");
        }
        return after;
    }

    private void shiftShotIndexes(List<DramaShot> shots, int fromIndex, int delta) {
        if (shots == null || shots.isEmpty() || delta == 0) {
            return;
        }
        List<DramaShot> moving = shots.stream()
                .filter(item -> item.getShotIndex() != null && item.getShotIndex() >= fromIndex)
                .sorted((left, right) -> delta > 0
                        ? Integer.compare(right.getShotIndex(), left.getShotIndex())
                        : Integer.compare(left.getShotIndex(), right.getShotIndex()))
                .toList();
        for (DramaShot item : moving) {
            item.setShotIndex(item.getShotIndex() + delta);
            shotMapper.updateById(item);
        }
    }

    private DramaChapter pickChapter(List<DramaChapter> chapters, Long chapterId) {
        if (chapterId == null) {
            return chapters.get(chapters.size() - 1);
        }
        return chapters.stream()
                .filter(item -> chapterId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "章节不属于本剧集"));
    }

    @Transactional
    public DramaShotResp confirmShot(Long shotId) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefix(shot.getSeriesId());
        rebindAndCompile(shot, assets, prefix, false, true);
        List<String> unbound = unboundLockedRequired(shot, assets);
        if (!unbound.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未锁定不能进分镜参考：" + String.join("、", unbound));
        }
        if (!StringUtils.hasText(effectivePrompt(shot)) || isStale(shot, assets, prefix)) {
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
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        DramaSeries series = requireOwnedSeries(shot.getSeriesId());
        DramaStoryboardCompiler.CompileOptions options = compileOptionsOf(series, shot);
        AiModel model = requireBoundVideoModel(series, options.videoGenerateMode());
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefixOf(series);
        boolean i2v = options.i2v();
        List<DramaAsset> refs;
        if (StringUtils.hasText(shot.getPromptOverride())) {
            refs = resolveReferences(shot, assets, !i2v, true);
        } else {
            rebindAndCompile(shot, assets, prefix, !i2v, true, series);
            refs = resolveReferences(shot, assets, !i2v, true);
            if (!StringUtils.hasText(shot.getCompiledPrompt())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请先编译分镜再出片");
            }
        }
        if (!i2v && refs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先锁定资产参考图再出片");
        }
        DramaShot prev = previousSameChapterShot(shot);
        if (i2v) {
            ensureVideoTail(prev);
        }
        boolean chainPrevTail = i2v && canChainPrevTail(shot, prev);
        String firstFrameKey = i2v ? resolveFirstFrameKey(shot, prev, chainPrevTail) : null;
        String prevTailKey = prev == null ? null : blankToNull(prev.getVideoTailKey());
        boolean chainPrev = !i2v && options.chainPrevTail() && StringUtils.hasText(prevTailKey);
        if (i2v && !StringUtils.hasText(firstFrameKey)) {
            assertCanStartOpeningStill(shot, prev);
            if (!StringUtils.hasText(shot.getPromptOverride())) {
                shotMapper.updateById(shot);
            }
            List<DramaAsset> stillRefs = resolveReferences(shot, assets, true, true);
            return startOpeningStill(shot, series, assets, prefix, stillRefs, true);
        }
        String adapter = AdapterCatalog.resolveKeyForGenerate(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        List<DramaAsset> sendRefs = i2v ? List.of() : refs;
        String prompt = effectivePrompt(shot);
        boolean includePrevTailPicture = chainPrev && !StringUtils.hasText(shot.getPromptOverride());
        if (!i2v && AdapterCatalog.isArk(adapter) && !StringUtils.hasText(shot.getPromptOverride())) {
            sendRefs = orderRefsForArk(refs);
            prompt = compileSubmitPrompt(shot, assets, prefix, sendRefs, options, includePrevTailPicture);
        } else if (includePrevTailPicture) {
            prompt = compileSubmitPrompt(shot, assets, prefix, sendRefs, options, true);
        } else if (i2v && !StringUtils.hasText(shot.getPromptOverride())) {
            prompt = compileSubmitPrompt(shot, assets, prefix, refs, options, false);
        }
        shotMapper.updateById(shot);

        abandonPreviousTask(shot);
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(model.getId());
        req.setCategory(ModelCategory.VIDEO);
        req.setPrompt(prompt);
        req.setRatio(series.getAspectRatio());
        req.setResolution(DramaResolutions.clamp(
                series.getDefaultResolution(), adapter, series.getVideoAdapterKey(), model.getAdapterKey(), model.getModelKey()));
        req.setDurationSec(DramaDurations.clamp(
                shot.getDurationSec(), series.getVideoAdapterKey(), model.getAdapterKey(), model.getModelKey()));
        req.setCopies(1);
        List<GenerateSubmitReq.ReferenceInput> inputs = new ArrayList<>();
        if (!i2v) {
            for (DramaAsset asset : sendRefs) {
                GenerateSubmitReq.ReferenceInput input = new GenerateSubmitReq.ReferenceInput();
                input.setType(AssetKind.IMAGE);
                if (StringUtils.hasText(asset.getImageStorageKey())) {
                    input.setStorageKey(asset.getImageStorageKey());
                } else if (asset.getImageAssetId() != null) {
                    input.setAssetId(asset.getImageAssetId());
                }
                inputs.add(input);
            }
            if (chainPrev) {
                GenerateSubmitReq.ReferenceInput tail = new GenerateSubmitReq.ReferenceInput();
                tail.setType(AssetKind.IMAGE);
                tail.setStorageKey(prevTailKey);
                inputs.add(tail);
            }
            if (supportsAudioReference(model)) {
                AudioReferenceCapabilities capability = audioCapabilities(model);
                List<DramaStoryboardCompiler.AudioReference> audioRefs = voiceReferences(shot, refs, true).stream()
                        .filter(item -> StringUtils.hasText(item.storageKey()))
                        .toList();
                if (audioRefs.size() > capability.maxReferences()) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "当前视频模型单镜最多引用 " + capability.maxReferences() + " 个角色音色，请拆分镜头");
                }
                for (DramaStoryboardCompiler.AudioReference audioRef : audioRefs) {
                    GenerateSubmitReq.ReferenceInput input = new GenerateSubmitReq.ReferenceInput();
                    input.setType(AssetKind.AUDIO);
                    input.setStorageKey(audioRef.storageKey());
                    inputs.add(input);
                }
            }
        }
        req.setReferences(inputs.isEmpty() ? null : inputs);
        if (i2v) {
            GenerateSubmitReq.FrameInput frames = new GenerateSubmitReq.FrameInput();
            frames.setFirstFrameKey(firstFrameKey);
            req.setFrames(frames);
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", GenerateRequests.SOURCE_DRAMA);
        extra.put("seriesId", series.getId());
        extra.put("episodeId", shot.getEpisodeId());
        extra.put("shotId", shot.getId());
        extra.put("adapterKey", adapter);
        extra.put("videoGenerateMode", options.videoGenerateMode());
        extra.put("chainPrevTail", chainPrev);
        if (i2v && chainPrevTail && StringUtils.hasText(prevTailKey) && prevTailKey.equals(firstFrameKey) && prev != null) {
            extra.put("chainedFromShotId", prev.getId());
            extra.put("chainedFromTailKey", prevTailKey);
        }
        log.info("drama generate shot {} series={} modelId={} adapter={} modelKey={} mode={} sendRefs={} firstFrame={}",
                shot.getId(), series.getId(), model.getId(), extra.get("adapterKey"), model.getModelKey(),
                extra.get("videoGenerateMode"), sendRefs.size(), firstFrameKey);
        GenerateTaskResp task = generateService.submit(req, extra);
        shot.setGenTaskId(task.getId());
        shotMapper.updateById(shot);
        touchSeries(series.getId());
        return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, task);
    }

    @Transactional
    public DramaShotGenerateResp getGenerateTask(Long shotId, Long taskId) {
        DramaShot shot = requireShot(shotId);
        GenerateTaskResp task = generateService.getMine(taskId);
        assertShotOwnedTask(shot, task);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefix(shot.getSeriesId());
        if (GenerateRequests.isOpeningStill(task.getParams())) {
            return continueOpeningStill(shot, task, assets, prefix);
        }
        if (shot.getGenTaskId() != null && !shot.getGenTaskId().equals(taskId)) {
            task = generateService.getMine(shot.getGenTaskId());
        }
        applyTaskResult(shot, task);
        return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, task);
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

    /**
     * 资产删除后只解除分镜参考图绑定，并重新编译提示词，不让整个分镜过期。
     * 分镜中的角色、场景和道具文字仍然保留，后续可继续依靠文字描述生成。
     */
    @Transactional
    public void detachAssetReference(Long episodeId, Long assetId) {
        if (episodeId == null || assetId == null) {
            return;
        }
        Map<Long, DramaAsset> assets = assetsById(episodeId);
        String prefix = stylePrefix(requireEpisode(episodeId).getSeriesId());
        for (DramaShot shot : listShots(episodeId)) {
            List<Long> references = idsOf(shot.getReferenceAssetIds());
            if (!references.contains(assetId)) {
                continue;
            }
            references.removeIf(id -> Objects.equals(id, assetId));
            shot.setReferenceAssetIds(references);
            // 只使用删除后仍存在的显式引用，避免自动绑定再次引入不必要的参考图。
            rebindAndCompile(shot, assets, prefix, false, false);
            shotMapper.updateById(shot);
        }
    }

    private void replaceStoryboard(
            DramaEpisode episode,
            ScriptDoc doc,
            boolean useLlm,
            Integer requestedDuration,
            Integer episodeBudgetSec) {
        long started = System.nanoTime();
        log.info("[drama] 分镙草稿落库开始 episodeId={} useLlm={} durationSec={}",
                episode.getId(), useLlm, requestedDuration);
        Long userId = UserContext.requireUserId();
        DramaSeries series = requireOwnedSeries(episode.getSeriesId());
        int fallback = resolveDraftDuration(series, requestedDuration);
        if (requestedDuration != null
                && DramaContract.DURATIONS.contains(requestedDuration)
                && !Objects.equals(series.getDefaultDurationSec(), fallback)) {
            series.setDefaultDurationSec(fallback);
            seriesMapper.updateById(series);
        }
        List<DramaAsset> assets = listAssets(episode.getId());
        List<String> assetNames = assets.stream()
                .map(DramaAsset::getName)
                .filter(StringUtils::hasText)
                .toList();
        ShotDraftList drafts = resolveDrafts(
                episode.getSeriesId(), doc, assetNames, fallback, useLlm, episodeBudgetSec);

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
            if (chapter == null && chapterIndex != null && !chapters.isEmpty()) {
                Integer nearest = null;
                for (Integer key : chapters.keySet()) {
                    if (nearest == null || Math.abs(key - chapterIndex) < Math.abs(nearest - chapterIndex)) {
                        nearest = key;
                    }
                }
                chapter = chapters.get(nearest);
                chapterIndex = nearest;
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
            shot.setDurationSec(resolveDraftDuration(series, draft.getDurationSec() == null ? fallback : draft.getDurationSec()));
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
            DramaVideoGenerateModes.applyProductDefault(shot);
            rebindAndCompile(shot, byId, prefix, false, true);
            shotMapper.insert(shot);
        }
        if (shotIndex == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未能生成分镜草稿");
        }
        touchSeries(episode.getSeriesId());
        log.info("[drama] 分镙草稿落库完成 episodeId={} shots={} elapsedMs={}",
                episode.getId(), shotIndex, (System.nanoTime() - started) / 1_000_000);
    }

    private ShotDraftList resolveDrafts(
            long seriesId,
            ScriptDoc doc,
            List<String> assetNames,
            int duration,
            boolean useLlm,
            Integer episodeBudgetSec) {
        DramaTextService svc = textService.getIfAvailable();
        ShotDraftList drafts = null;
        if (useLlm && svc == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "剧本生成服务尚未就绪");
        }
        if (useLlm) {
            drafts = svc.draftShots(seriesId, doc, assetNames, duration, episodeBudgetSec);
        }
        if (!useLlm && (drafts == null || drafts.getShots() == null || drafts.getShots().isEmpty())) {
            drafts = DramaShotDrafts.fromScript(doc, assetNames, duration);
        }
        if (drafts == null || drafts.getShots() == null || drafts.getShots().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未能生成分镜草稿");
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
        if (req.getVideoGenerateMode() != null) {
            boolean inherit = DramaVideoGenerateModes.isInherit(req.getVideoGenerateMode());
            String stored = DramaVideoGenerateModes.stored(req.getVideoGenerateMode());
            shot.setVideoGenerateMode(stored);
            if (DramaVideoGenerateModes.isI2v(stored)) {
                shot.setVideoChainPrevTail(false);
            } else if (req.getVideoChainPrevTail() != null) {
                shot.setVideoChainPrevTail(Boolean.TRUE.equals(req.getVideoChainPrevTail()));
            } else if (inherit || shot.getVideoChainPrevTail() == null) {
                shot.setVideoChainPrevTail(false);
            }
        } else if (req.getVideoChainPrevTail() != null) {
            shot.setVideoChainPrevTail(Boolean.TRUE.equals(req.getVideoChainPrevTail()));
        }
    }

    private void rebindAndCompile(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix,
                                 boolean requireLocked, boolean autoBindIfEmpty) {
        rebindAndCompile(shot, assets, stylePrefix, requireLocked, autoBindIfEmpty, seriesOf(shot));
    }

    private void rebindAndCompile(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix,
                                 boolean requireLocked, boolean autoBindIfEmpty, DramaSeries series) {
        List<DramaAsset> ordered = resolveReferences(shot, assets, requireLocked, autoBindIfEmpty);
        shot.setReferenceAssetIds(ordered.stream().map(DramaAsset::getId).toList());
        shot.setCastCount(cleanNames(shot.getCharacterNames()).size());
        DramaStoryboardCompiler.CompileOptions options = compileOptionsOf(series, shot);
        List<DramaStoryboardCompiler.ActiveReference> refs = new ArrayList<>();
        for (DramaAsset asset : ordered) {
            refs.add(toActive(asset));
        }
        List<DramaStoryboardCompiler.AudioReference> audioRefs = options.i2v()
                ? List.of()
                : voiceReferences(shot, ordered, supportsAudioReference(shot.getSeriesId()));
        DramaStoryboardCompiler.Result compiled = DramaStoryboardCompiler.compile(
                stylePrefix,
                refs,
                audioRefs,
                toShotContent(shot, assets, options.i2v() ? List.of() : ordered),
                options);
        shot.setCompiledPrompt(compiled.prompt());
        shot.setCompiledFingerprint(compiled.fingerprint());
    }

    private static String effectivePrompt(DramaShot shot) {
        if (StringUtils.hasText(shot.getPromptOverride())) {
            return shot.getPromptOverride();
        }
        return shot.getCompiledPrompt();
    }

    private boolean applyPromptOverride(DramaShot shot, DramaShotSaveReq req) {
        if (req.getPromptOverride() == null) {
            return StringUtils.hasText(shot.getPromptOverride());
        }
        String trimmed = StringUtils.hasText(req.getPromptOverride()) ? req.getPromptOverride().trim() : null;
        shot.setPromptOverride(trimmed);
        return trimmed != null;
    }

    private void rebindReferencesOnly(DramaShot shot, Map<Long, DramaAsset> assets,
                                      boolean requireLocked, boolean autoBindIfEmpty) {
        List<DramaAsset> ordered = resolveReferences(shot, assets, requireLocked, autoBindIfEmpty);
        shot.setReferenceAssetIds(ordered.stream().map(DramaAsset::getId).toList());
        shot.setCastCount(cleanNames(shot.getCharacterNames()).size());
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
        List<String> characterOrder = characterBindOrder(shot);
        for (String name : characterOrder) {
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
        return pickNamedAsset(assets == null ? List.of() : assets.values(), name, type, preferDream);
    }

    /**
     * 同名多条（基础 / 入梦 / 换装，或 state_tag 空与 base 并存）时，优先用已锁定且有图的那条。
     * 否则分镜仍按未锁定基础态判定「未锁定」，资产页却显示另一条已锁定。
     */
    static DramaAsset pickNamedAsset(Iterable<DramaAsset> assets, String name, String type, boolean preferDream) {
        if (assets == null || !StringUtils.hasText(name) || !StringUtils.hasText(type)) {
            return null;
        }
        DramaAsset eligibleDream = null;
        DramaAsset eligibleBase = null;
        DramaAsset eligibleAny = null;
        DramaAsset dream = null;
        DramaAsset base = null;
        DramaAsset any = null;
        for (DramaAsset asset : assets) {
            if (asset == null || !type.equals(asset.getType()) || !nameEquals(asset.getName(), name)) {
                continue;
            }
            any = asset;
            boolean dreamTag = DramaContract.STATE_TAG_DREAM.equals(asset.getStateTag());
            boolean baseTag = DramaContract.STATE_TAG_BASE.equals(asset.getStateTag())
                    || !StringUtils.hasText(asset.getStateTag());
            if (dreamTag) {
                dream = asset;
            } else if (baseTag) {
                base = asset;
            }
            if (isReferenceEligible(asset)) {
                eligibleAny = asset;
                if (dreamTag) {
                    eligibleDream = asset;
                } else if (baseTag) {
                    eligibleBase = asset;
                }
            }
        }
        if (preferDream) {
            if (eligibleDream != null) {
                return eligibleDream;
            }
            if (eligibleBase != null) {
                return eligibleBase;
            }
            if (eligibleAny != null) {
                return eligibleAny;
            }
            if (dream != null) {
                return dream;
            }
            return base != null ? base : any;
        }
        if (eligibleBase != null) {
            return eligibleBase;
        }
        if (eligibleAny != null) {
            return eligibleAny;
        }
        if (base != null) {
            return base;
        }
        return dream != null ? dream : any;
    }

    private static boolean nameEquals(String left, String right) {
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
        return refreshStale(shot, assets, stylePrefix, seriesOf(shot));
    }

    private boolean refreshStale(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix, DramaSeries series) {
        if (StringUtils.hasText(shot.getPromptOverride())) {
            return false;
        }
        boolean empty = !StringUtils.hasText(shot.getCompiledPrompt());
        boolean stale = isStale(shot, assets, stylePrefix, series);
        if (!empty && !stale) {
            return false;
        }
        rebindAndCompile(shot, assets, stylePrefix, false, true, series);
        if (stale && DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
            shot.setStatus(DramaContract.STATUS_EXPIRED);
        }
        shotMapper.updateById(shot);
        return true;
    }

    private boolean isStale(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix) {
        return isStale(shot, assets, stylePrefix, seriesOf(shot));
    }

    private boolean isStale(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix, DramaSeries series) {
        if (!StringUtils.hasText(shot.getCompiledFingerprint())) {
            return StringUtils.hasText(shot.getCompiledPrompt()) || StringUtils.hasText(shot.getPromptOverride());
        }
        DramaStoryboardCompiler.CompileOptions options = compileOptionsOf(series, shot);
        List<DramaAsset> pictured = new ArrayList<>();
        List<DramaStoryboardCompiler.ActiveReference> refs = new ArrayList<>();
        for (Long id : idsOf(shot.getReferenceAssetIds())) {
            DramaAsset asset = assets.get(id);
            if (asset != null) {
                pictured.add(asset);
                refs.add(toActive(asset));
            }
        }
        String current = DramaStoryboardCompiler.fingerprint(
                stylePrefix,
                refs,
                options.i2v() ? List.of() : voiceReferences(shot, pictured, supportsAudioReference(shot.getSeriesId())),
                toShotContent(shot, assets, options.i2v() ? List.of() : pictured),
                options);
        return !current.equals(shot.getCompiledFingerprint());
    }

    private List<DramaStoryboardCompiler.AudioReference> voiceReferences(
            DramaShot shot, List<DramaAsset> assets, boolean includeAudioKey) {
        Set<String> cast = new LinkedHashSet<>(cleanNames(shot.getCharacterNames()));
        Set<String> speakers = DramaStoryboardCompiler.spokenUtterances(shot.getDialogue()).stream()
                .map(DramaStoryboardCompiler.SpokenUtterance::speaker)
                .filter(cast::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<DramaStoryboardCompiler.AudioReference> values = new ArrayList<>();
        for (DramaAsset asset : assets) {
            if (!DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())
                    || !cast.contains(asset.getName())
                    || !speakers.contains(asset.getName())
                    || (!StringUtils.hasText(asset.getVoiceLock())
                    && (!includeAudioKey || !StringUtils.hasText(asset.getVoiceReferenceKey())))) {
                continue;
            }
            values.add(new DramaStoryboardCompiler.AudioReference(
                    asset.getName(), asset.getVoiceLock(), includeAudioKey ? asset.getVoiceReferenceKey() : null));
        }
        return values;
    }

    private boolean supportsAudioReference(Long seriesId) {
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series == null || series.getVideoModelId() == null) return false;
        AiModel model = aiModelMapper.selectById(series.getVideoModelId());
        return model != null && supportsAudioReference(model);
    }

    private static boolean supportsAudioReference(AiModel model) {
        return audioCapabilities(model).supported();
    }

    private static AudioReferenceCapabilities audioCapabilities(AiModel model) {
        return AudioReferenceCapabilities.resolve(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
    }

    private static String resolvedAdapter(AiModel model) {
        return AdapterCatalog.resolveKeyForGenerate(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
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
        Map<Long, GenTask> inflight = loadInFlightDramaTasks(shots);
        List<DramaChapterResp> chapterResps = new ArrayList<>();
        for (DramaChapter chapter : chapters) {
            DramaChapterResp resp = toChapterResp(chapter);
            List<DramaShotResp> shotResps = new ArrayList<>();
            for (DramaShot shot : byChapter.getOrDefault(chapter.getId(), List.of())) {
                DramaShotResp item = toShotResp(shot, chapter, assets, prefix,
                        resolveShotTask(shot, tasks, inflight));
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
        board.setDrafting(draftLock.isActive(episode));
        board.setDraftingAt(Boolean.TRUE.equals(board.getDrafting()) ? episode.getStoryboardDraftingAt() : null);
        board.setTextServiceAvailable(textService.getIfAvailable() != null);
        board.setLockedAssetCount(locked.size());
        board.setStaleShotCount(stale);
        board.setChapters(chapterResps);
        board.setLockedAssets(locked.stream().map(this::toLockedAsset).toList());
        var film = DramaFilms.describe(episode, shots);
        board.setFilmUrl(film.getFilmUrl());
        board.setFilmStale(film.getStale());
        DramaSeries series = seriesMapper.selectById(episode.getSeriesId());
        board.setVideoGenerateMode(DramaVideoGenerateModes.canonical(series == null ? null : series.getVideoGenerateMode()));
        board.setVideoChainPrevTail(series != null && Boolean.TRUE.equals(series.getVideoChainPrevTail()));
        board.setPromptRecompiling(series != null && recompileLock.isActive(series.getId()));
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
        resp.setPromptOverride(shot.getPromptOverride());
        resp.setUnboundNames(unboundLockedRequired(shot, assets));
        resp.setStatus(shot.getStatus());
        resp.setGenTaskId(shot.getGenTaskId());
        resp.setVideoKey(shot.getVideoKey());
        resp.setVideoUrl(MediaUrls.of(shot.getVideoKey()));
        resp.setVideoPosterKey(shot.getVideoPosterKey());
        resp.setVideoPosterUrl(MediaUrls.of(shot.getVideoPosterKey()));
        resp.setVideoTailKey(shot.getVideoTailKey());
        resp.setVideoTailUrl(MediaUrls.of(shot.getVideoTailKey()));
        resp.setFirstFrameKey(shot.getFirstFrameKey());
        resp.setFirstFrameUrl(MediaUrls.of(shot.getFirstFrameKey()));
        DramaSeries series = seriesOf(shot);
        DramaStoryboardCompiler.CompileOptions options = compileOptionsOf(series, shot);
        resp.setVideoGenerateMode(blankToNull(shot.getVideoGenerateMode()));
        resp.setVideoChainPrevTail(shot.getVideoChainPrevTail());
        resp.setVideoModeInherited(DramaVideoGenerateModes.inheritsMode(shot));
        resp.setVideoChainInherited(DramaVideoGenerateModes.inheritsChain(shot));
        resp.setEffectiveVideoGenerateMode(options.videoGenerateMode());
        resp.setEffectiveVideoChainPrevTail(options.chainPrevTail());
        resp.setVideoGeneratedMode(shot.getVideoGeneratedMode());
        resp.setChainedFromShotId(shot.getChainedFromShotId());
        DramaShot prev = previousSameChapterShot(shot);
        ensureVideoTail(prev);
        String firstFrameKey = resolveFirstFrameKey(shot, prev);
        resp.setEffectiveFirstFrameKey(firstFrameKey);
        resp.setEffectiveFirstFrameUrl(MediaUrls.of(firstFrameKey));
        if (StringUtils.hasText(shot.getFirstFrameKey())) {
            if ("prev-tail".equalsIgnoreCase(shot.getFirstFrameOrigin())) {
                resp.setFirstFrameSource("prev-tail");
            } else if ("auto".equalsIgnoreCase(shot.getFirstFrameOrigin())) {
                resp.setFirstFrameSource("auto");
            } else {
                resp.setFirstFrameSource("upload");
            }
            resp.setFirstFrameGap(null);
        } else if (StringUtils.hasText(firstFrameKey)) {
            resp.setFirstFrameSource("prev-tail");
            resp.setFirstFrameGap(null);
        } else {
            resp.setFirstFrameSource("none");
            resp.setFirstFrameGap(describeFirstFrameGap(shot, prev));
        }
        resp.setCanUsePrevTail(canUsePrevTail(shot, prev));
        resp.setGeneratingStill(task != null
                && GenerateRequests.isOpeningStill(task.getParams())
                && isGeneratingStatus(task.getStatus()));
        resp.setPrevTailStale(isPrevTailStale(shot, prev));
        if (task != null) {
            String status = GenerateTaskViews.publicStatus(task);
            resp.setGenTaskStatus(status);
            resp.setGenErrorMessage(GenerateTaskViews.publicError(task));
            resp.setGenerating(isGeneratingStatus(status));
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
        GenTask live = task == null ? null : loadTask(shot.getGenTaskId());
        resp.setShot(toShotResp(shot, chapter, assets, prefix, live));
        if (resp.getShot() != null && task != null && live == null) {
            resp.getShot().setGenerating(isGeneratingStatus(task.getStatus()));
            resp.getShot().setGenTaskStatus(task.getStatus());
            resp.getShot().setGenErrorMessage(task.getErrorMessage());
            resp.getShot().setGenTaskId(shot.getGenTaskId());
            resp.getShot().setGeneratingStill(GenerateRequests.isOpeningStill(task.getParams())
                    && isGeneratingStatus(task.getStatus()));
        }
        resp.setTask(task);
        return resp;
    }

    private void applySucceededQuietly(List<DramaShot> shots) {
        for (DramaShot shot : shots) {
            if (shot.getGenTaskId() != null) {
                try {
                    GenerateTaskResp task = generateService.getMine(shot.getGenTaskId());
                    applyTaskResult(shot, task);
                } catch (BizException ignored) {
                    // 任务不属于当前用户或已删：列表仍返回分镜本身
                }
            }
        }
    }

    private void applyTaskResult(DramaShot shot, GenerateTaskResp task) {
        if (task == null || !TaskStatus.SUCCEEDED.equals(task.getStatus())) {
            return;
        }
        if (GenerateRequests.isOpeningStill(task.getParams())) {
            applyOpeningStillResult(shot, task);
            return;
        }
        String key = firstOutputKey(task);
        String poster = blankToNull(task.getPosterKey());
        String tail = blankToNull(task.getTailKey());
        if (!StringUtils.hasText(tail) && task.getParams() != null && task.getParams().get("tailKey") != null) {
            tail = blankToNull(String.valueOf(task.getParams().get("tailKey")));
        }
        if (!StringUtils.hasText(tail) && StringUtils.hasText(key) && posterExtractor != null) {
            try {
                tail = blankToNull(posterExtractor.extractTail(key));
            } catch (RuntimeException e) {
                log.warn("extract tail failed, shot={} key={}", shot.getId(), key, e);
            }
        }
        boolean videoChanged = StringUtils.hasText(key) && !key.equals(shot.getVideoKey());
        boolean posterChanged = StringUtils.hasText(poster) && !poster.equals(shot.getVideoPosterKey());
        boolean tailChanged = StringUtils.hasText(tail) && !tail.equals(shot.getVideoTailKey());
        String generatedMode = task.getParams() == null || task.getParams().get("videoGenerateMode") == null
                ? null
                : DramaVideoGenerateModes.canonical(String.valueOf(task.getParams().get("videoGenerateMode")));
        Long chainedFromShotId = longParam(task.getParams(), "chainedFromShotId");
        String chainedFromTailKey = task.getParams() == null || task.getParams().get("chainedFromTailKey") == null
                ? null
                : blankToNull(String.valueOf(task.getParams().get("chainedFromTailKey")));
        boolean metaChanged = (generatedMode != null && !generatedMode.equals(shot.getVideoGeneratedMode()))
                || !Objects.equals(chainedFromShotId, shot.getChainedFromShotId())
                || !Objects.equals(chainedFromTailKey, shot.getChainedFromTailKey());
        if (!videoChanged && !posterChanged && !tailChanged && !metaChanged) {
            return;
        }
        if (videoChanged) {
            shot.setVideoKey(key);
            if (StringUtils.hasText(poster)) {
                shot.setVideoPosterKey(poster);
            }
            if (StringUtils.hasText(tail)) {
                shot.setVideoTailKey(tail);
            }
        } else {
            if (posterChanged) {
                shot.setVideoPosterKey(poster);
            }
            if (tailChanged) {
                shot.setVideoTailKey(tail);
            }
        }
        if (generatedMode != null) {
            shot.setVideoGeneratedMode(generatedMode);
        }
        shot.setChainedFromShotId(chainedFromShotId);
        shot.setChainedFromTailKey(chainedFromTailKey);
        shotMapper.updateById(shot);
        publishTailToNextShot(shot);
    }

    private void abandonPreviousTask(DramaShot shot) {
        if (shot.getGenTaskId() == null) {
            return;
        }
        GenTask previous = genTaskMapper.selectById(shot.getGenTaskId());
        if (previous == null || !isGeneratingStatus(previous.getStatus())) {
            return;
        }
        previous.setStatus(TaskStatus.CANCELLED);
        previous.setErrorMessage("已重新出片");
        previous.setFinishedAt(OffsetDateTime.now());
        genTaskMapper.updateById(previous);
    }

    private AiModel requireBoundVideoModel(DramaSeries series) {
        return requireBoundVideoModel(series, DramaVideoGenerateModes.canonical(series.getVideoGenerateMode()));
    }

    private AiModel requireBoundVideoModel(DramaSeries series, String videoGenerateMode) {
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
        String mode = DramaVideoGenerateModes.canonical(videoGenerateMode);
        String adapter = resolvedAdapter(model);
        if (DramaVideoGenerateModes.isI2v(mode)) {
            boolean ok = adapter != null
                    ? AdapterCatalog.supportsMode(adapter, GenerateMode.I2V)
                    : GenerateRequests.supportsMode(model.getCapabilities(), ModelCategory.VIDEO, GenerateMode.I2V);
            if (!ok) {
                throw new BizException(ErrorCode.BAD_REQUEST, "当前视频模型不支持首尾帧");
            }
        } else {
            boolean ok = adapter != null
                    ? AdapterCatalog.supportsMode(adapter, GenerateMode.R2V)
                    : GenerateRequests.supportsMode(model.getCapabilities(), ModelCategory.VIDEO, GenerateMode.R2V);
            if (!ok) {
                throw new BizException(ErrorCode.BAD_REQUEST, "当前视频模型不支持参考生视频");
            }
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
            return new HashMap<>();
        }
        Map<Long, GenTask> tasks = new HashMap<>();
        List<GenTask> found = genTaskMapper.selectBatchIds(ids);
        if (found == null) {
            return tasks;
        }
        for (GenTask task : found) {
            tasks.put(task.getId(), task);
        }
        return tasks;
    }

    /**
     * 刷新后不能只认镜头上的 genTaskId：新任务已入队但尚未回写、或仍指向已取消的旧任务时，
     * 按 params.shotId 找回进行中的漫剧出片任务。
     */
    private Map<Long, GenTask> loadInFlightDramaTasks(List<DramaShot> shots) {
        if (shots == null || shots.isEmpty()) {
            return Map.of();
        }
        Long episodeId = shots.get(0).getEpisodeId();
        List<GenTask> found = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .in(GenTask::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING)
                .apply("params->>'source' = {0}", GenerateRequests.SOURCE_DRAMA)
                .apply(episodeId != null, "params->>'episodeId' = {0}",
                        episodeId == null ? null : String.valueOf(episodeId))
                .last("LIMIT 80"));
        if (found == null || found.isEmpty()) {
            return Map.of();
        }
        Set<Long> shotIds = new HashSet<>();
        for (DramaShot shot : shots) {
            shotIds.add(shot.getId());
        }
        Map<Long, GenTask> byShot = new HashMap<>();
        for (GenTask task : found) {
            Long shotId = GenerateRequests.shotIdFromParams(task.getParams());
            if (shotId == null || !shotIds.contains(shotId)) {
                continue;
            }
            GenTask current = byShot.get(shotId);
            if (current == null || task.getId() > current.getId()) {
                byShot.put(shotId, task);
            }
        }
        return byShot;
    }

    private GenTask resolveShotTask(DramaShot shot, Map<Long, GenTask> tasks, Map<Long, GenTask> inflight) {
        GenTask bound = shot.getGenTaskId() == null || tasks == null ? null : tasks.get(shot.getGenTaskId());
        GenTask live = inflight == null ? null : inflight.get(shot.getId());
        if (live != null && isGeneratingStatus(live.getStatus())) {
            if (!Objects.equals(shot.getGenTaskId(), live.getId())) {
                shot.setGenTaskId(live.getId());
                shotMapper.updateById(shot);
                if (tasks != null) {
                    tasks.put(live.getId(), live);
                }
            }
            return live;
        }
        return bound;
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

    static boolean isActiveGenerateTask(GenTask task) {
        return task != null
                && !TaskStatus.CANCELLED.equals(task.getStatus())
                && (!TaskStatus.FAILED.equals(task.getStatus()) || GenerateTaskViews.shouldKeepPolling(task));
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
        return new DramaStoryboardCompiler.ActiveReference(
                asset.getId(),
                asset.getIdentityVersion() == null ? 0 : asset.getIdentityVersion(),
                asset.getName(),
                asset.getType(),
                "",
                asset.getIdentityInvariants());
    }

    private DramaStoryboardCompiler.ShotContent toShotContent(
            DramaShot shot, Map<Long, DramaAsset> assets, List<DramaAsset> pictured) {
        List<String> characterOrder = characterBindOrder(shot);
        return new DramaStoryboardCompiler.ShotContent(
                shot.getScene(),
                shot.getNarrativeGoal(),
                shot.getDialogue(),
                shot.getContinuityIn(),
                shot.getContinuityOut(),
                shot.getDurationSec() == null ? DramaContract.DEFAULT_DURATION_SEC : shot.getDurationSec(),
                characterOrder,
                characterOrder.size(),
                unnamedAppearances(characterOrder, assets, pictured, isDreamContext(shot)));
    }

    private static List<String> characterBindOrder(DramaShot shot) {
        List<String> namedCharacters = cleanNames(shot.getCharacterNames());
        List<String> onScreen = DramaNameMentions.mentionedInOrder(
                namedCharacters,
                shot.getNarrativeGoal(),
                shot.getDialogue(),
                shot.getContinuityIn(),
                shot.getContinuityOut());
        List<String> characterOrder = new ArrayList<>(onScreen);
        for (String name : namedCharacters) {
            if (!characterOrder.contains(name)) {
                characterOrder.add(name);
            }
        }
        return characterOrder;
    }

    private List<DramaStoryboardCompiler.NamedAppearance> unnamedAppearances(
            List<String> characterOrder, Map<Long, DramaAsset> assets, List<DramaAsset> pictured, boolean dream) {
        Set<String> picturedNames = new LinkedHashSet<>();
        if (pictured != null) {
            for (DramaAsset asset : pictured) {
                if (asset != null
                        && DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())
                        && StringUtils.hasText(asset.getName())) {
                    picturedNames.add(asset.getName().trim());
                }
            }
        }
        List<DramaStoryboardCompiler.NamedAppearance> unnamed = new ArrayList<>();
        for (String name : characterOrder) {
            if (picturedNames.contains(name)) {
                continue;
            }
            DramaAsset asset = findAsset(assets, name, DramaContract.ASSET_TYPE_CHARACTER, dream);
            unnamed.add(new DramaStoryboardCompiler.NamedAppearance(
                    name, asset == null ? "" : asset.getIdentityInvariants()));
        }
        return unnamed;
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

    private int resolveDraftDuration(DramaSeries series, Integer requested) {
        String adapter = series == null ? null : series.getVideoAdapterKey();
        String modelKey = null;
        if (series != null && series.getVideoModelId() != null) {
            AiModel model = aiModelMapper.selectById(series.getVideoModelId());
            if (model != null) {
                if (!StringUtils.hasText(adapter)) {
                    adapter = model.getAdapterKey();
                }
                modelKey = model.getModelKey();
            }
        }
        Integer want = requested;
        if (want == null && series != null) {
            want = series.getDefaultDurationSec();
        }
        return DramaDurations.clamp(want, adapter, modelKey);
    }

    private String compileSubmitPrompt(
            DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix, List<DramaAsset> pictured) {
        return compileSubmitPrompt(shot, assets, stylePrefix, pictured, compileOptionsOf(seriesOf(shot), shot), false);
    }

    private String compileSubmitPrompt(
            DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix, List<DramaAsset> pictured,
            DramaStoryboardCompiler.CompileOptions options, boolean includePrevTailPicture) {
        DramaStoryboardCompiler.CompileOptions opts = options == null ? DramaStoryboardCompiler.CompileOptions.r2v() : options;
        List<DramaStoryboardCompiler.ActiveReference> active = new ArrayList<>();
        if (pictured != null) {
            for (DramaAsset asset : pictured) {
                active.add(toActive(asset));
            }
        }
        if (!opts.i2v() && includePrevTailPicture) {
            active.add(DramaStoryboardCompiler.prevTailReference());
            opts = opts.withPrevTailPicture();
        }
        List<DramaStoryboardCompiler.AudioReference> audioRefs = opts.i2v()
                ? List.of()
                : voiceReferences(shot, pictured == null ? List.of() : pictured, supportsAudioReference(shot.getSeriesId()));
        return DramaStoryboardCompiler.compile(
                stylePrefix, active, audioRefs,
                toShotContent(shot, assets, opts.i2v() ? List.of() : pictured), opts).prompt();
    }

    /**
     * 官方 Seedance 报错指向 content[1]，即第一张参考图。角色设定图正脸放第一张最容易被拒，场景/道具先行。
     */
    static List<DramaAsset> orderRefsForArk(List<DramaAsset> refs) {
        List<DramaAsset> scenes = new ArrayList<>();
        List<DramaAsset> props = new ArrayList<>();
        List<DramaAsset> rest = new ArrayList<>();
        if (refs != null) {
            for (DramaAsset asset : refs) {
                if (asset == null) {
                    continue;
                }
                if (DramaContract.ASSET_TYPE_SCENE.equals(asset.getType())) {
                    scenes.add(asset);
                } else if (DramaContract.ASSET_TYPE_PROP.equals(asset.getType())) {
                    props.add(asset);
                } else {
                    rest.add(asset);
                }
            }
        }
        List<DramaAsset> ordered = new ArrayList<>(scenes.size() + props.size() + rest.size());
        ordered.addAll(scenes);
        ordered.addAll(props);
        ordered.addAll(rest);
        return ordered;
    }

    private static String stylePrefixOf(DramaSeries series) {
        String prefix;
        if (series != null && StringUtils.hasText(series.getStylePrefix())) {
            prefix = series.getStylePrefix();
        } else {
            prefix = DramaStyles.require(series == null ? DramaStyles.ANIME_COMIC : series.getStyleKey()).prefix();
        }
        return DramaStyles.withVideoSceneClause(prefix);
    }

    private void requireNotDrafting(Long episodeId) {
        requireNotDrafting(episodeMapper.selectById(episodeId));
    }

    private void requireNotDrafting(DramaEpisode episode) {
        if (draftLock.isActive(episode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, DramaStoryboardDraftLock.BUSY_MESSAGE);
        }
    }

    private void requireNotRecompiling(DramaShot shot) {
        if (shot != null) {
            requireNotRecompilingSeries(shot.getSeriesId());
        }
    }

    private void requireNotRecompiling(DramaEpisode episode) {
        if (episode != null) {
            requireNotRecompilingSeries(episode.getSeriesId());
        }
    }

    private void requireNotRecompilingSeries(Long seriesId) {
        if (recompileLock.isActive(seriesId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, DramaPromptRecompileLock.BUSY_MESSAGE);
        }
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

    private DramaSeries seriesOf(DramaShot shot) {
        return shot == null || shot.getSeriesId() == null ? null : seriesMapper.selectById(shot.getSeriesId());
    }

    private static DramaStoryboardCompiler.CompileOptions compileOptionsOf(DramaSeries series, DramaShot shot) {
        String mode = DramaVideoGenerateModes.effectiveMode(series, shot);
        boolean chain = DramaVideoGenerateModes.effectiveChainPrevTail(series, shot);
        return DramaStoryboardCompiler.CompileOptions.of(mode, chain);
    }

    private DramaShot previousSameChapterShot(DramaShot shot) {
        if (shot == null || shot.getEpisodeId() == null || shot.getShotIndex() == null || shot.getChapterId() == null) {
            return null;
        }
        DramaShot prev = null;
        for (DramaShot item : listShots(shot.getEpisodeId())) {
            if (item == null
                    || item.getShotIndex() == null
                    || item.getShotIndex() >= shot.getShotIndex()
                    || !shot.getChapterId().equals(item.getChapterId())) {
                continue;
            }
            if (prev == null || item.getShotIndex() > prev.getShotIndex()) {
                prev = item;
            }
        }
        return prev;
    }

    private DramaShot nextSameChapterShot(DramaShot shot) {
        if (shot == null || shot.getEpisodeId() == null || shot.getShotIndex() == null || shot.getChapterId() == null) {
            return null;
        }
        DramaShot next = null;
        for (DramaShot item : listShots(shot.getEpisodeId())) {
            if (item == null
                    || item.getShotIndex() == null
                    || item.getShotIndex() <= shot.getShotIndex()
                    || !shot.getChapterId().equals(item.getChapterId())) {
                continue;
            }
            if (next == null || item.getShotIndex() < next.getShotIndex()) {
                next = item;
            }
        }
        return next;
    }

    private void publishTailToNextShot(DramaShot shot) {
        if (shot == null || !StringUtils.hasText(shot.getVideoTailKey())) {
            return;
        }
        DramaShot next = nextSameChapterShot(shot);
        if (next == null
                || !DramaShotScales.chainable(shot, next)
                || "upload".equalsIgnoreCase(next.getFirstFrameOrigin())) {
            return;
        }
        if (!StringUtils.hasText(next.getFirstFrameKey()) && next.getFirstFrameOrigin() == null) {
            return;
        }
        next.setFirstFrameKey(null);
        next.setFirstFrameOrigin(null);
        shotMapper.updateById(next);
    }

    private boolean canUsePrevTail(DramaShot shot, DramaShot prev) {
        if (shot == null || prev == null || !DramaShotScales.sameChapter(prev, shot) || isShotInFlight(prev)) {
            return false;
        }
        return StringUtils.hasText(prev.getVideoTailKey()) || StringUtils.hasText(prev.getVideoKey());
    }

    private String ensureVideoTail(DramaShot shot) {
        if (shot == null) {
            return null;
        }
        if (StringUtils.hasText(shot.getVideoTailKey())) {
            return shot.getVideoTailKey().trim();
        }
        if (!StringUtils.hasText(shot.getVideoKey()) || posterExtractor == null) {
            return null;
        }
        try {
            String tail = blankToNull(posterExtractor.extractTail(shot.getVideoKey()));
            if (StringUtils.hasText(tail)) {
                shot.setVideoTailKey(tail);
                shotMapper.updateById(shot);
                return tail;
            }
        } catch (RuntimeException e) {
            log.warn("ensure tail failed, shot={} key={}", shot.getId(), shot.getVideoKey(), e);
        }
        return null;
    }

    private void assertCanStartOpeningStill(DramaShot shot, DramaShot prev) {
        if (prev == null || !DramaShotScales.chainable(prev, shot)) {
            return;
        }
        if (isShotInFlight(prev)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请等上一镜出片完成后再生成，本镜会自动用其落幅起幅");
        }
        if (StringUtils.hasText(prev.getVideoKey()) && !StringUtils.hasText(prev.getVideoTailKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上一镜落幅尚未抽出，请稍后再生成当前镜头");
        }
    }

    private boolean isShotInFlight(DramaShot shot) {
        if (shot == null || shot.getGenTaskId() == null || StringUtils.hasText(shot.getVideoKey())) {
            return false;
        }
        GenTask live = genTaskMapper.selectById(shot.getGenTaskId());
        return live != null && isGeneratingStatus(live.getStatus());
    }

    private boolean canChainPrevTail(DramaShot shot, DramaShot prev) {
        return prev != null
                && StringUtils.hasText(prev.getVideoTailKey())
                && DramaShotScales.chainable(prev, shot);
    }

    private String describeFirstFrameGap(DramaShot shot, DramaShot prev) {
        if (prev == null) {
            DramaShot episodePrev = previousEpisodeShot(shot);
            return episodePrev == null ? "no-prev" : "chapter-change";
        }
        if (!DramaShotScales.scaleCompatible(prev, shot)) {
            return "close-cut";
        }
        if (!DramaShotScales.castCompatible(prev, shot)) {
            return "cast-enter";
        }
        if (isShotInFlight(prev)) {
            return "prev-pending";
        }
        if (!StringUtils.hasText(prev.getVideoKey())) {
            return "prev-empty";
        }
        if (!cn.aigc.dit.generate.ffmpeg.FfmpegSupport.available()) {
            return "ffmpeg-missing";
        }
        if (!StringUtils.hasText(prev.getVideoTailKey())) {
            return "tail-missing";
        }
        return null;
    }

    private DramaShot previousEpisodeShot(DramaShot shot) {
        if (shot == null || shot.getEpisodeId() == null || shot.getShotIndex() == null) {
            return null;
        }
        DramaShot prev = null;
        for (DramaShot item : listShots(shot.getEpisodeId())) {
            if (item == null || item.getShotIndex() == null || item.getShotIndex() >= shot.getShotIndex()) {
                continue;
            }
            if (prev == null || item.getShotIndex() > prev.getShotIndex()) {
                prev = item;
            }
        }
        return prev;
    }

    private static String resolveFirstFrameKey(DramaShot shot, DramaShot prev) {
        boolean chain = shot != null && prev != null
                && StringUtils.hasText(prev.getVideoTailKey())
                && DramaShotScales.chainable(prev, shot);
        return resolveFirstFrameKey(shot, prev, chain);
    }

    private static String resolveFirstFrameKey(DramaShot shot, DramaShot prev, boolean chainPrevTail) {
        if (shot != null && StringUtils.hasText(shot.getFirstFrameKey())) {
            return shot.getFirstFrameKey().trim();
        }
        if (chainPrevTail && prev != null && StringUtils.hasText(prev.getVideoTailKey())) {
            return prev.getVideoTailKey().trim();
        }
        return null;
    }

    private DramaShotGenerateResp startOpeningStill(
            DramaShot shot,
            DramaSeries series,
            Map<Long, DramaAsset> assets,
            String prefix,
            List<DramaAsset> refs,
            boolean continueToVideo) {
        if (refs == null || refs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先锁定角色/场景设定图，用于自动画开场静帧");
        }
        AiModel image = requireBoundImageModel(series);
        String prevTailKey = resolveStillPrevTailKey(shot);
        List<DramaStoryboardCompiler.ActiveReference> active = new ArrayList<>();
        for (DramaAsset asset : refs) {
            active.add(toActive(asset));
        }
        if (StringUtils.hasText(prevTailKey)) {
            active.add(DramaStoryboardCompiler.prevTailStillReference());
        }
        String prompt = DramaStoryboardCompiler.compileStill(prefix, active, toShotContent(shot, assets, refs)).prompt();
        abandonPreviousTask(shot);
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(image.getId());
        req.setCategory(ModelCategory.IMAGE);
        req.setPrompt(prompt);
        req.setRatio(series.getAspectRatio());
        req.setResolution("1K");
        req.setCopies(1);
        if (imageSupportsI2i(image)) {
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
            if (StringUtils.hasText(prevTailKey)) {
                GenerateSubmitReq.ReferenceInput tail = new GenerateSubmitReq.ReferenceInput();
                tail.setType(AssetKind.IMAGE);
                tail.setStorageKey(prevTailKey);
                inputs.add(tail);
            }
            req.setReferences(inputs);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", GenerateRequests.SOURCE_DRAMA);
        extra.put("seriesId", series.getId());
        extra.put("episodeId", shot.getEpisodeId());
        extra.put("shotId", shot.getId());
        extra.put("openingStill", true);
        extra.put("stillForI2v", continueToVideo);
        extra.put("stillChainPrevTail", StringUtils.hasText(prevTailKey));
        GenerateTaskResp task = generateService.submit(req, extra);
        shot.setGenTaskId(task.getId());
        shotMapper.updateById(shot);
        touchSeries(series.getId());
        log.info("drama opening still shot {} series={} continueToVideo={} stillChainPrevTail={} task={}",
                shot.getId(), series.getId(), continueToVideo, StringUtils.hasText(prevTailKey), task.getId());
        return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, task);
    }

    private String resolveStillPrevTailKey(DramaShot shot) {
        DramaShot prev = previousSameChapterShot(shot);
        if (prev == null || isShotInFlight(prev)) {
            return null;
        }
        return blankToNull(ensureVideoTail(prev));
    }

    private DramaShotGenerateResp continueOpeningStill(
            DramaShot shot, GenerateTaskResp task, Map<Long, DramaAsset> assets, String prefix) {
        if (isGeneratingStatus(task.getStatus())) {
            return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, task);
        }
        if (!TaskStatus.SUCCEEDED.equals(task.getStatus())) {
            return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, task);
        }
        applyOpeningStillResult(shot, task);
        if (GenerateRequests.continueStillToI2v(task.getParams())) {
            if (shot.getGenTaskId() == null || shot.getGenTaskId().equals(task.getId())) {
                return generateShot(shot.getId());
            }
            GenerateTaskResp video = generateService.getMine(shot.getGenTaskId());
            applyTaskResult(shot, video);
            return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, video);
        }
        return toGenerateResp(shot, chapterOf(shot.getChapterId()), assets, prefix, task);
    }

    private void applyOpeningStillResult(DramaShot shot, GenerateTaskResp task) {
        if (task == null || !TaskStatus.SUCCEEDED.equals(task.getStatus())) {
            return;
        }
        String key = blankToNull(firstOutputKey(task));
        if (!StringUtils.hasText(key) || key.equals(shot.getFirstFrameKey())) {
            return;
        }
        shot.setFirstFrameKey(key);
        shot.setFirstFrameOrigin("auto");
        shotMapper.updateById(shot);
    }

    private void assertShotOwnedTask(DramaShot shot, GenerateTaskResp task) {
        Long paramShot = task == null ? null : GenerateRequests.shotIdFromParams(task.getParams());
        if (paramShot != null && paramShot.equals(shot.getId())) {
            return;
        }
        if (shot.getGenTaskId() != null && task != null && shot.getGenTaskId().equals(task.getId())) {
            return;
        }
        throw new BizException(ErrorCode.NOT_FOUND, "生成任务不存在");
    }

    private AiModel requireBoundImageModel(DramaSeries series) {
        if (series.getImageModelId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先在资产页选择图像模型，用于自动画开场静帧");
        }
        AiModel model = aiModelMapper.selectById(series.getImageModelId());
        if (model == null || !ModelCategory.IMAGE.equalsIgnoreCase(model.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目绑定的图像模型不存在");
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目绑定的图像模型已停用，请在资产页更换");
        }
        return model;
    }

    private static boolean imageSupportsI2i(AiModel model) {
        String adapter = resolvedAdapter(model);
        if (adapter != null) {
            return AdapterCatalog.supportsMode(adapter, GenerateMode.I2I);
        }
        return GenerateRequests.supportsMode(model.getCapabilities(), ModelCategory.IMAGE, GenerateMode.I2I);
    }

    private static boolean isPrevTailStale(DramaShot shot, DramaShot prev) {
        if (shot == null || !DramaVideoGenerateModes.isI2v(shot.getVideoGeneratedMode())) {
            return false;
        }
        if (shot.getChainedFromShotId() == null || !StringUtils.hasText(shot.getChainedFromTailKey())) {
            return false;
        }
        if (prev == null || !shot.getChainedFromShotId().equals(prev.getId())) {
            return true;
        }
        return !shot.getChainedFromTailKey().equals(prev.getVideoTailKey());
    }

    private static Long longParam(Map<String, Object> params, String key) {
        if (params == null || params.get(key) == null) {
            return null;
        }
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
