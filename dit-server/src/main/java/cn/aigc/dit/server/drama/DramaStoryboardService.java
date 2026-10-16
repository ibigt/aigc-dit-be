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
import cn.aigc.dit.generate.ImageReferenceCapabilities;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaChapter;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.entity.DramaShotCaption;
import cn.aigc.dit.server.drama.entity.DramaShotGeneration;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaChapterMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotGenerationMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.model.req.DramaShotCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaShotSaveReq;
import cn.aigc.dit.server.drama.model.resp.DramaChapterResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotGenerationResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotReferenceResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotResp;
import cn.aigc.dit.server.drama.model.resp.DramaStoryboardResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Asset;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
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
    private final DramaShotGenerationMapper shotGenerationMapper;
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
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private DramaShotBatchGenerateService batchGenerateService;
    @Autowired(required = false)
    private DramaShotCaptionPostProcessService captionPostProcessService;

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
            ScriptDoc script = ScriptDocs.fromMap(episode.getScript());
            Map<Long, DramaChapter> chaptersById = new HashMap<>();
            for (DramaChapter chapter : chapters) {
                if (chapter != null && chapter.getId() != null) {
                    chaptersById.put(chapter.getId(), chapter);
                }
            }
            for (DramaShot shot : shots) {
                boolean restored = restoreMissingSceneMention(
                        shot, chaptersById.get(shot.getChapterId()), script, assets);
                boolean refreshed = refreshStale(shot, assets, stylePrefix, series);
                if (restored && !refreshed) {
                    shotMapper.updateById(shot);
                }
                if (restored || refreshed) {
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
     * 拆章节并生成分镜草稿。始终使用项目绑定的文本模型；{@code useLlm} 仅保留调用签名。
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
            DramaStoryboardResp board = finishDraft(episodeId, durationSec, episodeBudgetSec);
            held = false;
            return board;
        } catch (RuntimeException e) {
            draftLock.fail(episodeId, e.getMessage());
            held = false;
            throw e;
        } finally {
            if (held) {
                draftLock.release(episodeId);
            }
        }
    }

    /**
     * 顶栏「重新拆镜」：同步占锁并立刻返回 drafting 状态，LLM 在后台跑。
     * 避免 HTTP 长时间无响应，前端看起来像点击没反应。
     */
    public DramaStoryboardResp startDraft(Long episodeId, Integer durationSec) {
        assertCanDraft(episodeId);
        LoginUser user = UserContext.require();
        draftLock.claim(episodeId);
        draftExecutor().execute(() -> {
            LoginUser previous = UserContext.get();
            UserContext.set(user);
            try {
                log.info("[drama] 手动拆镜开始 episodeId={} durationSec={}", episodeId, durationSec);
                ops().runClaimedDraft(episodeId, durationSec, null);
                log.info("[drama] 手动拆镜完成 episodeId={}", episodeId);
            } catch (Exception e) {
                log.warn("手动拆镜失败 episodeId={}: {}", episodeId, e.toString());
                try {
                    draftLock.fail(episodeId, e.getMessage());
                } catch (RuntimeException ignored) {
                    log.warn("手动拆镜记录失败原因失败 episodeId={}", episodeId);
                }
            } finally {
                if (previous != null) {
                    UserContext.set(previous);
                } else {
                    UserContext.clear();
                }
            }
        });
        return getStoryboard(episodeId);
    }

    /**
     * 分析结束后立刻占锁，让分镜页能显示「正在自动拆镜中」，真正的 LLM 拆镜在后台跑。
     */
    public void enqueueAutoDraft(Long episodeId, Integer episodeBudgetSec) {
        enqueueAutoDraft(episodeId, episodeBudgetSec, null);
    }

    /**
     * 与 {@link #enqueueAutoDraft(Long, Integer)} 相同：同步占拆镜锁，后台再跑。
     * {@code beforeDraft} 在真正拆镜前执行（如资产 LLM 增强），失败只打日志不阻断拆镜。
     * 占锁发生在入队当下，避免「分析已完成但尚未占锁」的空窗里被手动点拆镜。
     */
    public void enqueueAutoDraft(Long episodeId, Integer episodeBudgetSec, Runnable beforeDraft) {
        log.info("[drama] 自动分镜排队 episodeId={} episodeBudgetSec={} hasBeforeDraft={}",
                episodeId, episodeBudgetSec, beforeDraft != null);
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
                if (beforeDraft != null) {
                    try {
                        log.info("[drama] 自动分镜前置任务开始 episodeId={}", episodeId);
                        beforeDraft.run();
                        log.info("[drama] 自动分镜前置任务完成 episodeId={}", episodeId);
                    } catch (Exception e) {
                        log.warn("[drama] 自动分镜前置任务失败，继续拆镜 episodeId={}: {}",
                                episodeId, e.toString());
                    }
                }
                log.info("[drama] 自动分镜开始 episodeId={}", episodeId);
                ops().runClaimedDraft(episodeId, null, episodeBudgetSec);
                log.info("[drama] 自动分镜完成 episodeId={}", episodeId);
            } catch (Exception e) {
                log.warn("自动拆镜失败 episodeId={}: {}", episodeId, e.toString());
                try {
                    draftLock.fail(episodeId, e.getMessage());
                } catch (RuntimeException ignored) {
                    log.warn("自动拆镜记录失败原因失败 episodeId={}", episodeId);
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
            DramaStoryboardResp board = finishDraft(episodeId, durationSec, episodeBudgetSec);
            held = false;
            return board;
        } catch (RuntimeException e) {
            draftLock.fail(episodeId, e.getMessage());
            held = false;
            throw e;
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
    }

    private DramaStoryboardResp finishDraft(
            Long episodeId, Integer durationSec, Integer episodeBudgetSec) {
        DramaEpisode episode = requireEpisode(episodeId);
        ScriptDoc doc = ScriptDocs.fromMap(episode.getScript());
        replaceStoryboard(episode, doc, durationSec, episodeBudgetSec);
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
        assertNotBatchLocked(shotId);
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
        if (req.getVisiblePrompt() != null) {
            applyVisiblePrompt(shot, req, assets);
            assembleStored(shot, assets, prefix, false);
        } else if (isDialogueEdit(req)) {
            applyDialogueEdit(shot, req, assets);
            assembleStored(shot, assets, prefix, false);
        } else {
            boolean autoBind = req.getReferenceAssetIds() == null || req.getReferenceAssetIds().isEmpty();
            boolean overrideActive = applyPromptOverride(shot, req);
            if (overrideActive || isLegacyOverride(shot)) {
                rebindReferencesOnly(shot, assets, false, autoBind);
            } else {
                rebindAndCompile(shot, assets, prefix, false, autoBind);
            }
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
        rebindAndCompile(shot, assets, stylePrefix(shot.getSeriesId()), false, true, requireOwnedSeries(shot.getSeriesId()), true);
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
            rebindAndCompile(shot, assets, prefix, false, true, series, true);
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
        DramaShot shot = requireEditableFrameShot(shotId);
        shot.setFirstFrameKey(storeFrameFile(shot, file, "首帧"));
        shot.setFirstFrameOrigin("upload");
        return finishFrameEdit(shot);
    }

    @Transactional
    public DramaShotResp uploadEndFrame(Long shotId, MultipartFile file) {
        DramaShot shot = requireEditableFrameShot(shotId);
        shot.setEndFrameKey(storeFrameFile(shot, file, "尾帧"));
        shot.setEndFrameOrigin("upload");
        return finishFrameEdit(shot);
    }

    @Transactional
    public DramaShotResp useAssetFirstFrame(Long shotId, Long assetId) {
        DramaShot shot = requireEditableFrameShot(shotId);
        shot.setFirstFrameKey(imageKeyOfLibraryAsset(assetId));
        shot.setFirstFrameOrigin("asset");
        return finishFrameEdit(shot);
    }

    @Transactional
    public DramaShotResp useAssetEndFrame(Long shotId, Long assetId) {
        DramaShot shot = requireEditableFrameShot(shotId);
        shot.setEndFrameKey(imageKeyOfLibraryAsset(assetId));
        shot.setEndFrameOrigin("asset");
        return finishFrameEdit(shot);
    }

    @Transactional
    public DramaShotResp clearFirstFrame(Long shotId) {
        DramaShot shot = requireEditableFrameShot(shotId);
        shot.setFirstFrameKey(null);
        shot.setFirstFrameOrigin(null);
        return finishFrameEdit(shot);
    }

    @Transactional
    public DramaShotResp clearEndFrame(Long shotId) {
        DramaShot shot = requireEditableFrameShot(shotId);
        shot.setEndFrameKey(null);
        shot.setEndFrameOrigin(null);
        return finishFrameEdit(shot);
    }

    /** 把上一镜尾帧复制到本镜首帧。优先用户设置的尾帧，没有时用上一镜成片落幅。不限同一场。 */
    @Transactional
    public DramaShotResp usePrevEndFrame(Long shotId) {
        DramaShot shot = requireEditableFrameShot(shotId);
        DramaShot prev = previousEpisodeShot(shot);
        if (prev == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有上一镜");
        }
        if (StringUtils.hasText(prev.getEndFrameKey())) {
            shot.setFirstFrameKey(prev.getEndFrameKey());
            shot.setFirstFrameOrigin("prev-end");
            return finishFrameEdit(shot);
        }
        if (isShotInFlight(prev)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请等上一镜出片完成后再加载尾帧");
        }
        String tail = ensureVideoTail(prev);
        if (!StringUtils.hasText(tail)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上一镜还没有尾帧");
        }
        shot.setFirstFrameKey(tail);
        shot.setFirstFrameOrigin("prev-tail");
        return finishFrameEdit(shot);
    }

    @Transactional
    public DramaShotResp usePrevTail(Long shotId) {
        DramaShot shot = requireShot(shotId);
        assertNotBatchLocked(shotId);
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
        assertNotBatchLocked(shotId);
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
     * 手工补一镜。可插到指定镜之后 / 之前（胶片条两侧的 +），否则挂到指定章节末尾。
     * 人物 / 道具 / 地点沿用锚点镜，避免新镜一进来就是空镜编不出 prompt。
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
        if (req != null && req.getAfterShotId() != null && req.getBeforeShotId() != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "afterShotId 与 beforeShotId 不能同时传");
        }
        List<DramaShot> shots = listShots(episodeId);
        DramaShot after = resolveAfterShot(episodeId, req);
        DramaShot before = resolveBeforeShot(episodeId, req);
        DramaChapter chapter;
        DramaShot template;
        int index;
        if (before != null) {
            chapter = chapterOf(before.getChapterId());
            template = before;
            index = before.getShotIndex() == null ? 1 : before.getShotIndex();
        } else if (after != null) {
            chapter = chapterOf(after.getChapterId());
            template = after;
            index = after.getShotIndex() + 1;
        } else {
            chapter = pickChapter(chapters, req == null ? null : req.getChapterId());
            if (chapter == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "章节不属于本剧集");
            }
            List<DramaShot> siblings = shots.stream()
                    .filter(item -> chapter.getId().equals(item.getChapterId()))
                    .toList();
            template = siblings.isEmpty() ? null : siblings.get(siblings.size() - 1);
            index = shots.stream().mapToInt(item -> item.getShotIndex() == null ? 0 : item.getShotIndex()).max().orElse(0) + 1;
        }
        if (chapter == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "章节不属于本剧集");
        }
        long siblingCount = shots.stream().filter(item -> chapter.getId().equals(item.getChapterId())).count();
        if (siblingCount >= DramaContract.MAX_SHOTS_PER_CHAPTER) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单场最多 " + DramaContract.MAX_SHOTS_PER_CHAPTER + " 镜");
        }
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
        assertNotBatchLocked(shotId);
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

    private DramaShot resolveBeforeShot(Long episodeId, DramaShotCreateReq req) {
        if (req == null || req.getBeforeShotId() == null) {
            return null;
        }
        DramaShot before = requireShot(req.getBeforeShotId());
        if (!episodeId.equals(before.getEpisodeId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "分镜不属于本剧集");
        }
        return before;
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
            throw new BizException(ErrorCode.BAD_REQUEST, "还没有参考图，不能进分镜参考：" + String.join("、", unbound));
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

    @Transactional(readOnly = true)
    public List<DramaShotGenerationResp> listGenerations(Long shotId) {
        DramaShot shot = requireShot(shotId);
        List<DramaShotGeneration> newestFirst = listGenerationEntities(shot.getId());
        int total = newestFirst.size();
        List<DramaShotGenerationResp> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            DramaShotGeneration item = newestFirst.get(i);
            DramaShotGenerationResp resp = toGenerationResp(item, shot.getSelectedGenerationId());
            // 按创建时间编号：最旧=第 1 次，最新=第 N 次（忽略入库时的竞态标签）
            resp.setLabel("第 " + (total - i) + " 次生成");
            out.add(resp);
        }
        return out;
    }

    @Transactional
    public DramaShotResp selectGeneration(Long shotId, Long generationId) {
        DramaShot shot = requireShot(shotId);
        if (DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分镜已锁定，无法切换最终分镜");
        }
        DramaShotGeneration generation = requireOwnedGeneration(shot, generationId);
        applySelectedGeneration(shot, generation);
        shotMapper.updateById(shot);
        touchSeries(shot.getSeriesId());
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefix(shot.getSeriesId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, prefix, loadTask(shot.getGenTaskId()));
    }

    @Transactional
    public void deleteGeneration(Long shotId, Long generationId) {
        DramaShot shot = requireShot(shotId);
        DramaShotGeneration generation = requireOwnedGeneration(shot, generationId);
        boolean selected = generation.getId().equals(shot.getSelectedGenerationId());
        if (selected && DramaContract.STATUS_LOCKED.equals(shot.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分镜已锁定，不能删除当前选用记录");
        }
        boolean dropTask = generation.getGenTaskId() != null
                && generation.getGenTaskId().equals(shot.getGenTaskId());
        DramaShotGeneration next = null;
        if (selected) {
            next = listGenerationEntities(shot.getId()).stream()
                    .filter(item -> item != null && !generation.getId().equals(item.getId()))
                    .findFirst()
                    .orElse(null);
            if (next != null) {
                applySelectedGeneration(shot, next);
            } else {
                clearSelectedVideo(shot);
            }
        }
        if (dropTask) {
            shot.setGenTaskId(selected
                    ? (next == null ? null : next.getGenTaskId())
                    : taskIdOfShotGeneration(shot.getSelectedGenerationId()));
        }
        if (selected || dropTask) {
            shotMapper.updateById(shot);
        }
        shotGenerationMapper.deleteById(generation.getId());
        touchSeries(shot.getSeriesId());
    }

    @Transactional
    public DramaShotGenerateResp generateShot(Long shotId) {
        assertNotBatchLocked(shotId);
        return doGenerateShot(shotId, false);
    }

    /** 批次编排内部出片；这轮仍强制参考图，避免和用户尾帧搅在一起。 */
    @Transactional
    public DramaShotGenerateResp generateShotForBatch(Long shotId) {
        return doGenerateShot(shotId, true);
    }

    private DramaShotGenerateResp doGenerateShot(Long shotId, boolean forceR2v) {
        DramaShot shot = requireShot(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        DramaSeries series = requireOwnedSeries(shot.getSeriesId());
        String mode = forceR2v
                ? DramaVideoGenerateModes.R2V
                : modeForSingleGenerate(series, shot);
        if (forceR2v) {
            shot.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        }
        boolean i2v = DramaVideoGenerateModes.isI2v(mode);
        AiModel model = requireBoundVideoModel(series, mode);
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        String prefix = stylePrefixOf(series);
        if (i2v && !StringUtils.hasText(shot.getFirstFrameKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先设置首帧");
        }
        boolean legacy = isLegacyOverride(shot);
        List<DramaAsset> refs;
        String prompt;
        if (i2v) {
            refs = List.of();
            if (legacy) {
                prompt = shot.getPromptOverride().trim();
            } else {
                if (!StringUtils.hasText(shot.getVisiblePrompt())) {
                    shot.setVisiblePrompt(blankToNull(renderVisible(shot, assets)));
                }
                storeAssembled(shot, assets, prefix, List.of(), shot.getVisiblePrompt(), series);
                prompt = shot.getCompiledPrompt();
                if (!StringUtils.hasText(prompt)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "请先编译分镜再出片");
                }
            }
        } else if (legacy) {
            boolean named = idsOf(shot.getReferenceAssetIds()).isEmpty();
            refs = resolveReferences(shot, assets, true, named);
            prompt = shot.getPromptOverride().trim();
        } else {
            if (!StringUtils.hasText(shot.getVisiblePrompt())) {
                shot.setVisiblePrompt(blankToNull(renderVisible(shot, assets)));
            }
            refs = resolveNarrativeReferences(shot, assets, shot.getVisiblePrompt(), true);
            shot.setReferenceAssetIds(refs.stream().map(DramaAsset::getId).toList());
            storeAssembled(shot, assets, prefix, refs, shot.getVisiblePrompt(), series);
            prompt = shot.getCompiledPrompt();
            if (!StringUtils.hasText(prompt)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请先编译分镜再出片");
            }
        }
        if (!i2v && refs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先准备资产参考图再出片");
        }
        if (!i2v) {
            int maxImages = ImageReferenceCapabilities.maxImages(
                    model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
            if (refs.size() > maxImages) {
                throw new BizException(ErrorCode.BAD_REQUEST, "当前模型最多支持 " + maxImages + " 张");
            }
        }
        String adapter = AdapterCatalog.resolveKeyForGenerate(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
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
        req.setReferences(inputs.isEmpty() ? null : inputs);
        if (i2v) {
            GenerateSubmitReq.FrameInput frames = new GenerateSubmitReq.FrameInput();
            frames.setFirstFrameKey(shot.getFirstFrameKey());
            if (StringUtils.hasText(shot.getEndFrameKey())) {
                frames.setLastFrameKey(shot.getEndFrameKey());
            }
            req.setFrames(frames);
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", GenerateRequests.SOURCE_DRAMA);
        extra.put("seriesId", series.getId());
        extra.put("episodeId", shot.getEpisodeId());
        extra.put("shotId", shot.getId());
        extra.put("adapterKey", adapter);
        extra.put("videoGenerateMode", mode);
        extra.put("chainPrevTail", false);
        log.info("drama generate shot {} series={} modelId={} adapter={} modelKey={} mode={} sendRefs={}",
                shot.getId(), series.getId(), model.getId(), extra.get("adapterKey"), model.getModelKey(),
                extra.get("videoGenerateMode"), refs.size());
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
            Integer requestedDuration,
            Integer episodeBudgetSec) {
        long started = System.nanoTime();
        log.info("[drama] 分镜草稿落库开始 episodeId={} durationSec={}",
                episode.getId(), requestedDuration);
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
                episode.getSeriesId(), doc, assetNames, fallback, episodeBudgetSec, visualStyleOf(series));

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
            DramaShotBeats beats = DramaShotBeats.normalize(draft.getShotBeats());
            draft.setShotBeats(beats);
            DramaShotBeats.syncDraftDialogue(draft);
            shot.setNarrativeGoal(blankToNull(draft.getNarrativeGoal()));
            shot.setCameraMotion(blankToNull(draft.getCamera()));
            shot.setAmbianceAudio(blankToNull(draft.getAmbiance()));
            shot.setShotBeats(beats);
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
            alignMentionedProps(shot, byId);
            shot.setLocationName(StringUtils.hasText(draft.getLocationName())
                    ? draft.getLocationName().trim()
                    : (scenes.get(chapterIndex) == null ? null : scenes.get(chapterIndex).getLocation()));
            shot.setCastCount(characters.size());
            shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            shot.setReferenceAssetIds(new ArrayList<>());
            DramaVideoGenerateModes.applyProductDefault(shot);
            // 仅草稿落库时补齐正文点名但 Cast 漏写的角色；之后用户侧栏增删不再被覆盖。
            alignMentionedCharacters(shot, byId);
            rebindAndCompile(shot, byId, prefix, false, true, series, true);
            shotMapper.insert(shot);
        }
        if (shotIndex == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未能生成分镜草稿");
        }
        touchSeries(episode.getSeriesId());
        log.info("[drama] 分镜草稿落库完成 episodeId={} shots={} elapsedMs={}",
                episode.getId(), shotIndex, (System.nanoTime() - started) / 1_000_000);
    }

    private ShotDraftList resolveDrafts(
            long seriesId,
            ScriptDoc doc,
            List<String> assetNames,
            int duration,
            Integer episodeBudgetSec,
            String visualStyle) {
        DramaTextService svc = textService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "剧本生成服务尚未就绪");
        }
        ShotDraftList drafts = svc.draftShots(seriesId, doc, assetNames, duration, episodeBudgetSec, visualStyle);
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
        if (req.getCameraMotion() != null) {
            shot.setCameraMotion(blankToNull(req.getCameraMotion()));
        }
        if (req.getAmbianceAudio() != null) {
            shot.setAmbianceAudio(blankToNull(req.getAmbianceAudio()));
        }
        if (req.getScene() != null) {
            shot.setScene(blankToNull(req.getScene()));
        }
        if (req.getDialogue() != null) {
            shot.setDialogue(blankToNull(req.getDialogue()));
        }
        if (req.getShotBeats() != null) {
            DramaShotBeats beats = DramaShotBeats.normalize(req.getShotBeats());
            shot.setShotBeats(beats);
            if (DramaShotBeats.hasBeats(beats)) {
                shot.setDialogue(DramaShotBeats.joinedDialogue(beats));
            }
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
            if (inherit) {
                shot.setVideoChainPrevTail(null);
            } else if (DramaVideoGenerateModes.isI2v(stored)) {
                shot.setVideoChainPrevTail(false);
            } else if (req.getVideoChainPrevTail() != null) {
                shot.setVideoChainPrevTail(Boolean.TRUE.equals(req.getVideoChainPrevTail()));
            }
        } else if (req.getVideoChainPrevTail() != null) {
            shot.setVideoChainPrevTail(Boolean.TRUE.equals(req.getVideoChainPrevTail()));
        }
    }

    private void rebindAndCompile(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix,
                                 boolean requireLocked, boolean autoBindIfEmpty) {
        rebindAndCompile(shot, assets, stylePrefix, requireLocked, autoBindIfEmpty, seriesOf(shot), false);
    }

    private void rebindAndCompile(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix,
                                 boolean requireLocked, boolean autoBindIfEmpty, DramaSeries series) {
        rebindAndCompile(shot, assets, stylePrefix, requireLocked, autoBindIfEmpty, series, false);
    }

    private void rebindAndCompile(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix,
                                 boolean requireLocked, boolean autoBindIfEmpty, DramaSeries series,
                                 boolean overwriteVisible) {
        if (isLegacyOverride(shot) && !overwriteVisible) {
            return;
        }
        String visible = visibleForCompile(shot, assets, overwriteVisible);
        if (overwriteVisible) {
            shot.setVisiblePrompt(blankToNull(visible));
            shot.setPromptOverride(null);
        }
        List<DramaAsset> ordered = resolveNarrativeReferences(shot, assets, visible, requireLocked);
        if (ordered.isEmpty() && !StringUtils.hasText(visible) && autoBindIfEmpty) {
            ordered = resolveReferences(shot, assets, requireLocked, true);
        }
        shot.setReferenceAssetIds(ordered.stream().map(DramaAsset::getId).toList());
        shot.setCastCount(cleanNames(shot.getCharacterNames()).size());
        storeAssembled(shot, assets, stylePrefix, ordered, visible, series);
    }

    private void applyVisiblePrompt(DramaShot shot, DramaShotSaveReq req, Map<Long, DramaAsset> assets) {
        String visible = DramaStoryboardCompiler.stripDialogueMentions(req.getVisiblePrompt());
        shot.setVisiblePrompt(blankToNull(visible));
        shot.setPromptOverride(null);
        if (DramaStoryboardCompiler.hasDialogueSlots(visible)) {
            shot.setDialogue(blankToNull(DramaStoryboardCompiler.extractDialogue(visible)));
        } else if (req.getDialogue() != null) {
            shot.setDialogue(blankToNull(DramaStoryboardCompiler.stripMentionMarks(req.getDialogue())));
        }
        if (req.getReferenceAssetIds() != null) {
            shot.setReferenceAssetIds(new ArrayList<>(req.getReferenceAssetIds()));
        }
        List<DramaAsset> ordered = orderedReferences(shot, assets, visible, false);
        shot.setReferenceAssetIds(ordered.stream().map(DramaAsset::getId).toList());
        backfillCast(shot, ordered);
    }

    private void applyDialogueEdit(DramaShot shot, DramaShotSaveReq req, Map<Long, DramaAsset> assets) {
        String base = shot.getVisiblePrompt();
        if (!StringUtils.hasText(base) && isLegacyOverride(shot)) {
            base = DramaStoryboardCompiler.readableLegacyOverride(shot.getPromptOverride());
        }
        if (!StringUtils.hasText(base)) {
            base = renderVisible(shot, assets);
        }
        if (DramaStoryboardCompiler.hasDialogueSlots(base)) {
            base = DramaStoryboardCompiler.writeDialogueBack(base, req.getDialogue());
        }
        base = DramaStoryboardCompiler.stripDialogueMentions(base);
        shot.setVisiblePrompt(blankToNull(base));
        shot.setPromptOverride(null);
        if (DramaStoryboardCompiler.hasDialogueSlots(base)) {
            shot.setDialogue(blankToNull(DramaStoryboardCompiler.extractDialogue(base)));
        } else {
            shot.setDialogue(blankToNull(DramaStoryboardCompiler.stripMentionMarks(req.getDialogue())));
        }
        List<DramaAsset> ordered = resolveNarrativeReferences(shot, assets, base, false);
        shot.setReferenceAssetIds(ordered.stream().map(DramaAsset::getId).toList());
    }

    private boolean isDialogueEdit(DramaShotSaveReq req) {
        return req.getDialogue() != null
                && req.getVisiblePrompt() == null
                && req.getPromptOverride() == null
                && req.getNarrativeGoal() == null
                && req.getShotBeats() == null
                && req.getScene() == null
                && req.getCameraMotion() == null
                && req.getAmbianceAudio() == null
                && req.getCharacterNames() == null
                && req.getPropNames() == null
                && req.getLocationName() == null
                && req.getReferenceAssetIds() == null
                && req.getContinuityIn() == null
                && req.getContinuityOut() == null;
    }

    private String visibleForCompile(DramaShot shot, Map<Long, DramaAsset> assets, boolean overwriteVisible) {
        if (!overwriteVisible && StringUtils.hasText(shot.getVisiblePrompt())) {
            return shot.getVisiblePrompt();
        }
        return renderVisible(shot, assets);
    }

    private String renderVisible(DramaShot shot, Map<Long, DramaAsset> assets) {
        return DramaStoryboardCompiler.renderVisiblePrompt(
                toShotContent(shot, assets, List.of()),
                mentionNames(shot, assets),
                shot.getLocationName(),
                cleanNames(shot.getPropNames()));
    }

    private void assembleStored(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix, boolean requireImage) {
        String visible = shot.getVisiblePrompt();
        List<DramaAsset> ordered = orderedReferences(shot, assets, visible, requireImage);
        shot.setReferenceAssetIds(ordered.stream().map(DramaAsset::getId).toList());
        shot.setCastCount(cleanNames(shot.getCharacterNames()).size());
        storeAssembled(shot, assets, stylePrefix, ordered, visible, seriesOf(shot));
    }

    private void storeAssembled(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix,
                                List<DramaAsset> ordered, String visible, DramaSeries series) {
        List<DramaStoryboardCompiler.ActiveReference> refs = new ArrayList<>();
        if (ordered != null) {
            for (DramaAsset asset : ordered) {
                refs.add(toActive(asset));
            }
        }
        Long seriesId = shot.getSeriesId() != null ? shot.getSeriesId() : (series == null ? null : series.getId());
        List<DramaStoryboardCompiler.AudioReference> audioRefs = voiceReferences(
                shot, ordered == null ? List.of() : ordered, supportsAudioReference(seriesId));
        DramaStoryboardCompiler.Result compiled = DramaStoryboardCompiler.assemble(
                stylePrefix,
                refs,
                audioRefs,
                visible,
                toShotContent(shot, assets, ordered));
        shot.setCompiledPrompt(compiled.prompt());
        shot.setCompiledFingerprint(compiled.fingerprint());
    }

    private List<DramaAsset> orderedReferences(DramaShot shot, Map<Long, DramaAsset> assets,
                                               String visible, boolean requireImage) {
        if (shot.getReferenceAssetIds() != null && !shot.getReferenceAssetIds().isEmpty()
                && requestPrefersExplicitIds(shot, visible)) {
            List<DramaAsset> explicit = resolveReferences(shot, assets, requireImage, false);
            if (!explicit.isEmpty() || !requireImage) {
                List<String> mentions = DramaStoryboardCompiler.narrativeAtNames(visible, assetNames(assets));
                if (explicitCoversMentions(explicit, mentions) || mentions.isEmpty()) {
                    return explicit;
                }
            }
        }
        return resolveNarrativeReferences(shot, assets, visible, requireImage);
    }

    private boolean requestPrefersExplicitIds(DramaShot shot, String visible) {
        return shot.getReferenceAssetIds() != null && !idsOf(shot.getReferenceAssetIds()).isEmpty()
                && StringUtils.hasText(visible);
    }

    private boolean explicitCoversMentions(List<DramaAsset> explicit, List<String> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return true;
        }
        Set<String> names = new LinkedHashSet<>();
        for (DramaAsset asset : explicit) {
            if (asset != null && StringUtils.hasText(asset.getName())) {
                names.add(asset.getName().trim());
            }
        }
        for (String mention : mentions) {
            if (!names.contains(mention)) {
                return false;
            }
        }
        return explicit.size() == mentions.size();
    }

    private void backfillCast(DramaShot shot, List<DramaAsset> ordered) {
        List<String> characters = new ArrayList<>();
        List<String> props = new ArrayList<>();
        String location = null;
        for (DramaAsset asset : ordered) {
            if (asset == null || !StringUtils.hasText(asset.getName())) {
                continue;
            }
            String name = asset.getName().trim();
            if (DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())) {
                if (!characters.contains(name)) {
                    characters.add(name);
                }
            } else if (DramaContract.ASSET_TYPE_PROP.equals(asset.getType())) {
                if (!props.contains(name)) {
                    props.add(name);
                }
            } else if (DramaContract.ASSET_TYPE_SCENE.equals(asset.getType()) && location == null) {
                location = name;
            }
        }
        shot.setCharacterNames(characters);
        shot.setPropNames(props);
        // 正文里没有场景 @ 时保留镜头已有地点。清空后，画面描述若只写「侦探社」
        // 而不是资产名「幻梦侦探社」，下次编译就补不回场景引用。
        if (location != null) {
            shot.setLocationName(location);
        }
        shot.setCastCount(characters.size());
    }

    /**
     * 地点为空时用本场剧本地点。对应场景已有参考图、而提示词场景行没有 @ 时补上。
     * 地点对不上已出图的场景资产时不动，避免给还没出图的场景硬加引用。
     */
    private boolean restoreMissingSceneMention(
            DramaShot shot, DramaChapter chapter, ScriptDoc script, Map<Long, DramaAsset> assets) {
        if (shot == null || isLegacyOverride(shot)) {
            return false;
        }
        String location = StringUtils.hasText(shot.getLocationName())
                ? shot.getLocationName().trim()
                : scriptLocation(chapter, script);
        if (!StringUtils.hasText(location)) {
            return false;
        }
        DramaAsset scene = findAsset(assets, location, DramaContract.ASSET_TYPE_SCENE, false);
        if (!isReferenceEligible(scene) || !StringUtils.hasText(scene.getName())) {
            return false;
        }
        String name = scene.getName().trim();
        boolean changed = false;
        if (!name.equals(StringUtils.hasText(shot.getLocationName()) ? shot.getLocationName().trim() : null)) {
            shot.setLocationName(name);
            changed = true;
        }
        String visible = shot.getVisiblePrompt();
        if (!StringUtils.hasText(visible) || visible.contains("@" + name)) {
            return changed;
        }
        String cited = citeScene(visible, name);
        if (cited.equals(visible)) {
            return changed;
        }
        shot.setVisiblePrompt(cited);
        return true;
    }

    private static String scriptLocation(DramaChapter chapter, ScriptDoc doc) {
        if (chapter == null || doc == null || doc.getScenes() == null) {
            return null;
        }
        Integer sceneIndex = chapter.getSceneIndex();
        if (sceneIndex != null) {
            for (ScriptDoc.Scene scene : doc.getScenes()) {
                if (scene != null && sceneIndex.equals(scene.getIndex()) && StringUtils.hasText(scene.getLocation())) {
                    return scene.getLocation().trim();
                }
            }
            return null;
        }
        int chapterIndex = chapter.getChapterIndex() == null ? -1 : chapter.getChapterIndex();
        if (chapterIndex > 0 && chapterIndex <= doc.getScenes().size()) {
            ScriptDoc.Scene scene = doc.getScenes().get(chapterIndex - 1);
            if (scene != null && StringUtils.hasText(scene.getLocation())) {
                return scene.getLocation().trim();
            }
        }
        return null;
    }

    /** 在「场景：」后补上已出图的场景名，画面描述原样保留。 */
    static String citeScene(String visible, String name) {
        if (!StringUtils.hasText(visible) || !StringUtils.hasText(name) || visible.contains("@" + name)) {
            return visible;
        }
        String label = "场景：";
        int at = visible.indexOf(label);
        if (at < 0) {
            label = "场景:";
            at = visible.indexOf(label);
        }
        String mark = "@" + name.trim() + "。";
        if (at < 0) {
            return "场景：" + mark + "\n" + visible;
        }
        int insertAt = at + label.length();
        return visible.substring(0, insertAt) + mark + visible.substring(insertAt);
    }

    private List<String> mentionNames(DramaShot shot, Map<Long, DramaAsset> assets) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (assets != null) {
            for (Long id : idsOf(shot.getReferenceAssetIds())) {
                DramaAsset asset = assets.get(id);
                if (isReferenceEligible(asset) && StringUtils.hasText(asset.getName())) {
                    names.add(asset.getName().trim());
                }
            }
        }
        List<String> candidates = new ArrayList<>();
        candidates.addAll(cleanNames(shot.getCharacterNames()));
        candidates.addAll(cleanNames(shot.getPropNames()));
        if (StringUtils.hasText(shot.getLocationName())) {
            candidates.add(shot.getLocationName().trim());
        }
        boolean dream = isDreamContext(shot);
        for (String name : candidates) {
            DramaAsset asset = findEligibleNamed(assets, name, dream);
            if (isReferenceEligible(asset)) {
                names.add(asset.getName().trim());
            }
        }
        for (String name : mentionedEligibleProps(assets, shot)) {
            names.add(name);
        }
        return new ArrayList<>(names);
    }

    /** 画面说明里点到、且已有参考图的道具。不看台词。 */
    private List<String> mentionedEligibleProps(Map<Long, DramaAsset> assets, DramaShot shot) {
        List<String> pool = new ArrayList<>();
        if (assets != null) {
            for (DramaAsset asset : assets.values()) {
                if (asset == null
                        || !DramaContract.ASSET_TYPE_PROP.equals(asset.getType())
                        || !isReferenceEligible(asset)
                        || !StringUtils.hasText(asset.getName())) {
                    continue;
                }
                String name = asset.getName().trim();
                if (!pool.contains(name)) {
                    pool.add(name);
                }
            }
        }
        return DramaNameMentions.mentionedExactInOrder(
                pool, DramaShotBeats.propMentionTexts(shot).toArray(String[]::new));
    }

    private List<DramaAsset> resolveNarrativeReferences(
            DramaShot shot, Map<Long, DramaAsset> assets, String visible, boolean requireImage) {
        List<String> mentions = DramaStoryboardCompiler.narrativeAtNames(visible, assetNames(assets));
        List<Long> preferred = idsOf(shot.getReferenceAssetIds());
        Set<Long> used = new HashSet<>();
        List<DramaAsset> ordered = new ArrayList<>();
        boolean dream = isDreamContext(shot);
        for (String name : mentions) {
            DramaAsset picked = null;
            for (Long id : preferred) {
                if (id == null || used.contains(id)) {
                    continue;
                }
                DramaAsset asset = assets.get(id);
                if (asset != null && nameEquals(asset.getName(), name)) {
                    picked = asset;
                    break;
                }
            }
            if (picked == null) {
                picked = findEligibleNamed(assets, name, dream);
            }
            if (picked == null || !isReferenceEligible(picked)) {
                if (requireImage) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "缺少参考图：" + name);
                }
                continue;
            }
            if (used.add(picked.getId())) {
                ordered.add(picked);
            }
        }
        return ordered;
    }

    private DramaAsset findEligibleNamed(Map<Long, DramaAsset> assets, String name, boolean dream) {
        DramaAsset character = findAsset(assets, name, DramaContract.ASSET_TYPE_CHARACTER, dream);
        DramaAsset prop = findAsset(assets, name, DramaContract.ASSET_TYPE_PROP, false);
        DramaAsset scene = findAsset(assets, name, DramaContract.ASSET_TYPE_SCENE, false);
        DramaAsset eligible = firstEligible(character, prop, scene);
        if (eligible != null) {
            return eligible;
        }
        if (character != null) {
            return character;
        }
        if (prop != null) {
            return prop;
        }
        return scene;
    }

    private static DramaAsset firstEligible(DramaAsset... assets) {
        if (assets == null) {
            return null;
        }
        for (DramaAsset asset : assets) {
            if (isReferenceEligible(asset)) {
                return asset;
            }
        }
        return null;
    }

    private static List<String> assetNames(Map<Long, DramaAsset> assets) {
        List<String> names = new ArrayList<>();
        if (assets == null) {
            return names;
        }
        for (DramaAsset asset : assets.values()) {
            if (asset != null && StringUtils.hasText(asset.getName()) && !names.contains(asset.getName().trim())) {
                names.add(asset.getName().trim());
            }
        }
        return names;
    }

    private static boolean isLegacyOverride(DramaShot shot) {
        return shot != null
                && StringUtils.hasText(shot.getPromptOverride())
                && !StringUtils.hasText(shot.getVisiblePrompt());
    }

    private String displayVisiblePrompt(DramaShot shot, Map<Long, DramaAsset> assets) {
        if (StringUtils.hasText(shot.getVisiblePrompt())) {
            return shot.getVisiblePrompt();
        }
        if (isLegacyOverride(shot)) {
            return DramaStoryboardCompiler.readableLegacyOverride(shot.getPromptOverride());
        }
        return renderVisible(shot, assets);
    }

    private List<String> missingNarrativeMentions(DramaShot shot, String visible, Map<Long, DramaAsset> assets) {
        List<String> missing = new ArrayList<>();
        boolean dream = isDreamContext(shot);
        for (String name : DramaStoryboardCompiler.narrativeAtNames(visible, assetNames(assets))) {
            DramaAsset asset = findEligibleNamed(assets, name, dream);
            if (!isReferenceEligible(asset) && !missing.contains(name)) {
                missing.add(name);
            }
        }
        return missing;
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
                        throw new BizException(ErrorCode.BAD_REQUEST, asset.getName() + "还没有参考图，不能进入分镜参考");
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
                throw new BizException(ErrorCode.BAD_REQUEST, asset.getName() + "还没有参考图，不能进入分镜参考");
            }
            return;
        }
        ordered.putIfAbsent(asset.getId(), asset);
    }

    private DramaAsset findAsset(Map<Long, DramaAsset> assets, String name, String type, boolean preferDream) {
        return pickNamedAsset(assets == null ? List.of() : assets.values(), name, type, preferDream);
    }

    /**
     * 同名多条（基础 / 入梦 / 换装，或 state_tag 空与 base 并存）时，优先用已有参考图的那条。
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
                String.join(" ", DramaShotBeats.mentionTexts(shot.getShotBeats())),
                String.join(" ", cleanNames(shot.getCharacterNames())));
        return blob.contains("入梦") || blob.contains("梦境") || blob.contains("梦魇");
    }

    private boolean refreshStale(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix) {
        return refreshStale(shot, assets, stylePrefix, seriesOf(shot));
    }

    private boolean refreshStale(DramaShot shot, Map<Long, DramaAsset> assets, String stylePrefix, DramaSeries series) {
        if (StringUtils.hasText(shot.getPromptOverride()) && !StringUtils.hasText(shot.getVisiblePrompt())) {
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
        if (isLegacyOverride(shot)) {
            return false;
        }
        if (!StringUtils.hasText(shot.getCompiledFingerprint())) {
            return StringUtils.hasText(shot.getCompiledPrompt()) || StringUtils.hasText(shot.getPromptOverride());
        }
        String visible = StringUtils.hasText(shot.getVisiblePrompt())
                ? shot.getVisiblePrompt()
                : renderVisible(shot, assets);
        List<DramaAsset> pictured = resolveNarrativeReferences(shot, assets, visible, false);
        List<DramaStoryboardCompiler.ActiveReference> refs = new ArrayList<>();
        for (DramaAsset asset : pictured) {
            refs.add(toActive(asset));
        }
        String current = DramaStoryboardCompiler.fingerprintVisible(
                stylePrefix,
                refs,
                voiceReferences(shot, pictured, supportsAudioReference(shot.getSeriesId())),
                toShotContent(shot, assets, pictured),
                visible);
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
        board.setDraftError(Boolean.TRUE.equals(board.getDrafting()) ? null : episode.getStoryboardDraftError());
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
        board.setActiveBatchJobId(batchGenerateService == null ? null : batchGenerateService.findActiveJobId(episode.getId()));
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
        resp.setCameraMotion(shot.getCameraMotion());
        resp.setAmbianceAudio(shot.getAmbianceAudio());
        resp.setShotBeats(shot.getShotBeats());
        resp.setScene(shot.getScene());
        resp.setDialogue(shot.getDialogue());
        resp.setContinuityIn(shot.getContinuityIn());
        resp.setContinuityOut(shot.getContinuityOut());
        boolean legacy = isLegacyOverride(shot);
        String displayPrompt = displayVisiblePrompt(shot, assets);
        List<DramaAsset> bound = legacy
                ? resolveReferences(shot, assets, false, idsOf(shot.getReferenceAssetIds()).isEmpty())
                : resolveNarrativeReferences(shot, assets, displayPrompt, false);
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
        resp.setVisiblePrompt(displayPrompt);
        resp.setLegacyOverride(legacy);
        resp.setPromptOverride(shot.getPromptOverride());
        resp.setUnboundNames(legacy
                ? unboundLockedRequired(shot, assets)
                : missingNarrativeMentions(shot, displayPrompt, assets));
        resp.setStatus(shot.getStatus());
        resp.setGenTaskId(shot.getGenTaskId());
        resp.setVideoKey(shot.getVideoKey());
        resp.setVideoUrl(MediaUrls.of(shot.getVideoKey()));
        if (captionPostProcessService != null) {
            // A processor upgrade also needs to schedule already generated shots.
            if (StringUtils.hasText(shot.getVideoKey())) {
                try {
                    captionPostProcessService.enqueue(
                            shot.getId(), shot.getGenTaskId(), shot.getUserId(), shot.getVideoKey());
                } catch (RuntimeException e) {
                    log.warn("enqueue existing shot caption failed, shot={}", shot.getId(), e);
                }
            }
            DramaShotCaption caption = captionPostProcessService.findForSource(shot.getId(), shot.getVideoKey());
            if (caption != null) {
                resp.setCaptionStatus(caption.getStatus());
                resp.setCaptionVideoKey(caption.getCaptionVideoKey());
                resp.setCaptionVideoUrl(MediaUrls.of(caption.getCaptionVideoKey()));
                resp.setSubtitleJsonKey(caption.getSubtitleJsonKey());
                resp.setSubtitleJsonUrl(MediaUrls.of(caption.getSubtitleJsonKey()));
            }
        }
        resp.setSelectedGenerationId(shot.getSelectedGenerationId());
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
            resp.setFirstFrameSource(frameSourceOf(shot.getFirstFrameOrigin()));
            resp.setFirstFrameGap(null);
        } else if (StringUtils.hasText(firstFrameKey)) {
            resp.setFirstFrameSource("prev-tail");
            resp.setFirstFrameGap(null);
        } else {
            resp.setFirstFrameSource("none");
            resp.setFirstFrameGap(describeFirstFrameGap(shot, prev));
        }
        resp.setEndFrameKey(shot.getEndFrameKey());
        resp.setEndFrameUrl(MediaUrls.of(shot.getEndFrameKey()));
        resp.setEndFrameSource(StringUtils.hasText(shot.getEndFrameKey())
                ? ("asset".equalsIgnoreCase(shot.getEndFrameOrigin()) ? "asset" : "upload")
                : "none");
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
                } catch (DataIntegrityViolationException e) {
                    // 与批次轮询并发落库偶发撞唯一键；savepoint 已兜底，这里再吞掉以免看板 500
                    log.debug("skip concurrent shot generation apply shot={} task={}: {}",
                            shot.getId(), shot.getGenTaskId(), e.getMessage());
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
            // 任务结果可能已入库；用户改选旧记录后 videoKey 与最新任务不一致，
            // 刷新/轮询时不得再插入并强行改选。
            if (findRecordedShotGeneration(shot.getId(), task.getId(), key) != null) {
                return;
            }
            insertAndSelectGeneration(shot, key, poster, tail, task.getId(), "generate");
            reopenIfConfirmed(shot);
        } else {
            if (posterChanged) {
                shot.setVideoPosterKey(poster);
            }
            if (tailChanged) {
                shot.setVideoTailKey(tail);
            }
            syncSelectedGenerationMedia(shot);
        }
        if (generatedMode != null) {
            shot.setVideoGeneratedMode(generatedMode);
        }
        shot.setChainedFromShotId(chainedFromShotId);
        shot.setChainedFromTailKey(chainedFromTailKey);
        shotMapper.updateById(shot);
        if (videoChanged && captionPostProcessService != null) {
            captionPostProcessService.enqueue(shot.getId(), task.getId(), shot.getUserId(), key);
        }
        if (videoChanged) {
            refreshChapterStatuses(listChapters(shot.getEpisodeId()), listShots(shot.getEpisodeId()));
        }
        publishTailToNextShot(shot);
    }

    private void insertAndSelectGeneration(
            DramaShot shot,
            String videoKey,
            String posterKey,
            String tailKey,
            Long genTaskId,
            String source) {
        // 批次轮询与看板刷新可能并发落库同一成功任务；先查再插，配合唯一索引兜底
        DramaShotGeneration existing = findRecordedShotGeneration(shot.getId(), genTaskId, videoKey);
        if (existing != null) {
            applySelectedGeneration(shot, existing);
            return;
        }
        DramaShotGeneration generation = new DramaShotGeneration();
        generation.setShotId(shot.getId());
        generation.setUserId(shot.getUserId());
        generation.setVideoKey(videoKey);
        generation.setVideoPosterKey(blankToNull(posterKey));
        generation.setVideoTailKey(blankToNull(tailKey));
        generation.setGenTaskId(genTaskId);
        generation.setSource(source);
        generation.setLabel(nextShotLabel(shot.getId()));
        // PG：唯一键冲突会中止整段事务；必须用 savepoint 回滚冲突后再查已有行
        Object savepoint = null;
        boolean inTx = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            if (inTx) {
                savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
            }
            shotGenerationMapper.insert(generation);
            if (savepoint != null) {
                TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);
            }
        } catch (DataIntegrityViolationException e) {
            if (savepoint != null) {
                TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
            } else {
                throw e;
            }
            DramaShotGeneration raced = findRecordedShotGeneration(shot.getId(), genTaskId, videoKey);
            if (raced == null) {
                throw e;
            }
            applySelectedGeneration(shot, raced);
            return;
        }
        applySelectedGeneration(shot, generation);
    }

    /** 同一任务或同一视频已有记录则视为已落库（避免改选后被任务回写重复插入）。 */
    private DramaShotGeneration findRecordedShotGeneration(Long shotId, Long genTaskId, String videoKey) {
        if (genTaskId != null) {
            DramaShotGeneration byTask = shotGenerationMapper.selectOne(new LambdaQueryWrapper<DramaShotGeneration>()
                    .eq(DramaShotGeneration::getShotId, shotId)
                    .eq(DramaShotGeneration::getGenTaskId, genTaskId)
                    .last("LIMIT 1"));
            if (byTask != null) {
                return byTask;
            }
        }
        if (!StringUtils.hasText(videoKey)) {
            return null;
        }
        return shotGenerationMapper.selectOne(new LambdaQueryWrapper<DramaShotGeneration>()
                .eq(DramaShotGeneration::getShotId, shotId)
                .eq(DramaShotGeneration::getVideoKey, videoKey)
                .last("LIMIT 1"));
    }

    private Long taskIdOfShotGeneration(Long generationId) {
        if (generationId == null) {
            return null;
        }
        DramaShotGeneration current = shotGenerationMapper.selectById(generationId);
        return current == null ? null : current.getGenTaskId();
    }

    private void applySelectedGeneration(DramaShot shot, DramaShotGeneration generation) {
        shot.setSelectedGenerationId(generation.getId());
        shot.setVideoKey(generation.getVideoKey());
        shot.setVideoPosterKey(generation.getVideoPosterKey());
        shot.setVideoTailKey(generation.getVideoTailKey());
    }

    private void clearSelectedVideo(DramaShot shot) {
        shot.setSelectedGenerationId(null);
        shot.setVideoKey(null);
        shot.setVideoPosterKey(null);
        shot.setVideoTailKey(null);
    }

    private void syncSelectedGenerationMedia(DramaShot shot) {
        if (shot.getSelectedGenerationId() == null) {
            return;
        }
        DramaShotGeneration generation = shotGenerationMapper.selectById(shot.getSelectedGenerationId());
        if (generation == null) {
            return;
        }
        generation.setVideoPosterKey(shot.getVideoPosterKey());
        generation.setVideoTailKey(shot.getVideoTailKey());
        shotGenerationMapper.updateById(generation);
    }

    private void reopenIfConfirmed(DramaShot shot) {
        if (DramaContract.STATUS_LOCKED.equals(shot.getStatus())
                || DramaContract.STATUS_EXPIRED.equals(shot.getStatus())) {
            shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
    }

    private DramaShotGeneration requireOwnedGeneration(DramaShot shot, Long generationId) {
        DramaShotGeneration generation = shotGenerationMapper.selectById(generationId);
        if (generation == null
                || !generation.getShotId().equals(shot.getId())
                || !generation.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "生成记录不存在");
        }
        return generation;
    }

    private List<DramaShotGeneration> listGenerationEntities(Long shotId) {
        return shotGenerationMapper.selectList(new LambdaQueryWrapper<DramaShotGeneration>()
                .eq(DramaShotGeneration::getShotId, shotId)
                .orderByDesc(DramaShotGeneration::getId));
    }

    private String nextShotLabel(Long shotId) {
        long count = shotGenerationMapper.selectCount(new LambdaQueryWrapper<DramaShotGeneration>()
                .eq(DramaShotGeneration::getShotId, shotId));
        return "第 " + (count + 1) + " 次生成";
    }

    private DramaShotGenerationResp toGenerationResp(DramaShotGeneration generation, Long selectedId) {
        DramaShotGenerationResp resp = new DramaShotGenerationResp();
        resp.setId(generation.getId());
        resp.setShotId(generation.getShotId());
        resp.setVideoKey(generation.getVideoKey());
        resp.setVideoUrl(MediaUrls.of(generation.getVideoKey()));
        resp.setVideoPosterKey(generation.getVideoPosterKey());
        resp.setVideoPosterUrl(MediaUrls.of(generation.getVideoPosterKey()));
        resp.setVideoTailKey(generation.getVideoTailKey());
        resp.setVideoTailUrl(MediaUrls.of(generation.getVideoTailKey()));
        resp.setGenTaskId(generation.getGenTaskId());
        resp.setSource(generation.getSource());
        resp.setLabel(generation.getLabel());
        resp.setSelected(generation.getId().equals(selectedId));
        resp.setCreatedAt(generation.getCreatedAt());
        return resp;
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

    /** 真正占用中的任务：排队/跑着，或官方 Seedance 误杀后仍需轮询。已成功不算占用。 */
    static boolean isActiveGenerateTask(GenTask task) {
        if (task == null) {
            return false;
        }
        if (isGeneratingStatus(task.getStatus())) {
            return true;
        }
        return TaskStatus.FAILED.equals(task.getStatus()) && GenerateTaskViews.shouldKeepPolling(task);
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
                unnamedAppearances(characterOrder, assets, pictured, isDreamContext(shot)),
                shot.getCameraMotion(),
                shot.getAmbianceAudio(),
                shot.getShotBeats());
    }

    /**
     * 草稿落库：把动作/台词/出入点里点到的本集角色并入名单（保留用户已写、正文未点名的）。
     * 仅用于首次生成分镜，不用于 update/compile，以免覆盖用户删除。
     */
    static void alignMentionedCharacters(DramaShot shot, Map<Long, DramaAsset> assets) {
        if (shot == null) {
            return;
        }
        List<String> pool = characterAssetNames(assets);
        List<String> texts = new ArrayList<>();
        texts.add(shot.getNarrativeGoal());
        texts.add(shot.getDialogue());
        texts.add(shot.getContinuityIn());
        texts.add(shot.getContinuityOut());
        texts.addAll(DramaShotBeats.mentionTexts(shot.getShotBeats()));
        List<String> mentioned = DramaNameMentions.mentionedExactInOrder(pool, texts.toArray(String[]::new));
        List<String> merged = new ArrayList<>(mentioned);
        for (String name : cleanNames(shot.getCharacterNames())) {
            if (!merged.contains(name)) {
                merged.add(name);
            }
        }
        shot.setCharacterNames(merged);
        shot.setCastCount(merged.size());
    }

    /** 镜头动作里点到的道具并入 prop_names，继续走现有参考图绑定。 */
    static void alignMentionedProps(DramaShot shot, Map<Long, DramaAsset> assets) {
        if (shot == null) {
            return;
        }
        List<String> pool = new ArrayList<>();
        if (assets != null) {
            for (DramaAsset asset : assets.values()) {
                if (asset == null
                        || !DramaContract.ASSET_TYPE_PROP.equals(asset.getType())
                        || !StringUtils.hasText(asset.getName())) {
                    continue;
                }
                String name = asset.getName().trim();
                if (!pool.contains(name)) {
                    pool.add(name);
                }
            }
        }
        List<String> mentioned = DramaNameMentions.mentionedExactInOrder(
                pool, DramaShotBeats.propMentionTexts(shot).toArray(String[]::new));
        List<String> merged = new ArrayList<>(cleanNames(shot.getPropNames()));
        for (String name : mentioned) {
            if (!merged.contains(name)) {
                merged.add(name);
            }
        }
        shot.setPropNames(merged);
    }

    private static List<String> characterAssetNames(Map<Long, DramaAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (DramaAsset asset : assets.values()) {
            if (asset == null
                    || !DramaContract.ASSET_TYPE_CHARACTER.equals(asset.getType())
                    || !StringUtils.hasText(asset.getName())) {
                continue;
            }
            String name = asset.getName().trim();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> characterBindOrder(DramaShot shot) {
        List<String> namedCharacters = cleanNames(shot.getCharacterNames());
        List<String> texts = new ArrayList<>();
        texts.add(shot.getNarrativeGoal());
        texts.add(shot.getDialogue());
        texts.add(shot.getContinuityIn());
        texts.add(shot.getContinuityOut());
        texts.addAll(DramaShotBeats.mentionTexts(shot.getShotBeats()));
        List<String> onScreen = DramaNameMentions.mentionedInOrder(namedCharacters, texts.toArray(String[]::new));
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

    /** 有当前选用图即可作为分镜参考，不要求资产已锁定。 */
    private static boolean isReferenceEligible(DramaAsset asset) {
        return asset != null && StringUtils.hasText(asset.getImageStorageKey());
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

    /** 扩写用的风格句，不含视频场面约束。陈设必须贴这句，不要另长出别的时代。 */
    private static String visualStyleOf(DramaSeries series) {
        DramaStyles.Spec spec = DramaStyles.require(
                series == null || !StringUtils.hasText(series.getStyleKey())
                        ? DramaStyles.ANIME_COMIC
                        : series.getStyleKey());
        String prefix = series != null && StringUtils.hasText(series.getStylePrefix())
                ? series.getStylePrefix().trim()
                : spec.prefix();
        return spec.name() + "。" + prefix;
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
            if (!StringUtils.hasText(name)) {
                continue;
            }
            String trimmed = name.trim();
            if (DramaDialogueSpeakers.isNarration(trimmed) || cleaned.contains(trimmed)) {
                continue;
            }
            cleaned.add(trimmed);
        }
        return cleaned;
    }

    static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private DramaShot requireEditableFrameShot(Long shotId) {
        DramaShot shot = requireShot(shotId);
        assertNotBatchLocked(shotId);
        requireNotDrafting(shot.getEpisodeId());
        requireNotRecompiling(shot);
        return shot;
    }

    private DramaShotResp finishFrameEdit(DramaShot shot) {
        shotMapper.updateById(shot);
        touchSeries(shot.getSeriesId());
        Map<Long, DramaAsset> assets = assetsById(shot.getEpisodeId());
        return toShotResp(shot, chapterOf(shot.getChapterId()), assets, stylePrefix(shot.getSeriesId()), loadTask(shot.getGenTaskId()));
    }

    private String storeFrameFile(DramaShot shot, MultipartFile file, String label) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择" + label + "图片");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!mime.startsWith("image/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "仅支持图片");
        }
        return assetService.putOwnedFile(file, "drama/" + shot.getSeriesId() + "/frames");
    }

    private String imageKeyOfLibraryAsset(Long assetId) {
        if (assetId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择素材");
        }
        Asset asset = assetService.requireOwned(assetId);
        if (asset == null
                || !AssetKind.IMAGE.equalsIgnoreCase(asset.getKind())
                || !StringUtils.hasText(asset.getStorageKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能引用素材库里的图片");
        }
        assetService.assertOwnedStorageKey(asset.getStorageKey());
        return asset.getStorageKey();
    }

    private static String frameSourceOf(String origin) {
        if ("prev-tail".equalsIgnoreCase(origin)) {
            return "prev-tail";
        }
        if ("prev-end".equalsIgnoreCase(origin)) {
            return "prev-end";
        }
        if ("auto".equalsIgnoreCase(origin)) {
            return "auto";
        }
        if ("asset".equalsIgnoreCase(origin)) {
            return "asset";
        }
        return "upload";
    }

    /** 单镜出片：已保存的方式优先；没保存过按参考图，模型只有首尾帧时改走首尾帧。 */
    private String modeForSingleGenerate(DramaSeries series, DramaShot shot) {
        String mode = shot != null && StringUtils.hasText(shot.getVideoGenerateMode())
                ? DramaVideoGenerateModes.canonical(shot.getVideoGenerateMode())
                : DramaVideoGenerateModes.R2V;
        if (DramaVideoGenerateModes.isR2v(mode)
                && !supportsBoundVideoMode(series, GenerateMode.R2V)
                && supportsBoundVideoMode(series, GenerateMode.I2V)) {
            return DramaVideoGenerateModes.I2V;
        }
        return mode;
    }

    private void assertNotBatchLocked(Long shotId) {
        if (batchGenerateService != null) {
            batchGenerateService.assertNotBatchLocked(shotId);
        }
    }

    private DramaSeries seriesOf(DramaShot shot) {
        return shot == null || shot.getSeriesId() == null ? null : seriesMapper.selectById(shot.getSeriesId());
    }

    private DramaStoryboardCompiler.CompileOptions compileOptionsOf(DramaSeries series, DramaShot shot) {
        boolean firstInChapter = isFirstShotInChapter(shot);
        String mode = DramaVideoGenerateModes.effectiveMode(series, shot);
        if (DramaVideoGenerateModes.isR2v(mode)
                && !supportsBoundVideoMode(series, GenerateMode.R2V)
                && supportsBoundVideoMode(series, GenerateMode.I2V)) {
            mode = DramaVideoGenerateModes.I2V;
        }
        boolean chain = !firstInChapter
                && DramaVideoGenerateModes.isR2v(mode)
                && resolveChainPrevTail(series, shot);
        return DramaStoryboardCompiler.CompileOptions.of(mode, chain);
    }

    private boolean supportsBoundVideoMode(DramaSeries series, String mode) {
        if (series == null || series.getVideoModelId() == null) {
            return false;
        }
        AiModel model = aiModelMapper.selectById(series.getVideoModelId());
        if (model == null || !ModelCategory.VIDEO.equalsIgnoreCase(model.getCategory())
                || !Boolean.TRUE.equals(model.getEnabled())) {
            return false;
        }
        String adapter = resolvedAdapter(model);
        return adapter != null
                ? AdapterCatalog.supportsMode(adapter, mode)
                : GenerateRequests.supportsMode(model.getCapabilities(), ModelCategory.VIDEO, mode);
    }

    private static boolean resolveChainPrevTail(DramaSeries series, DramaShot shot) {
        return DramaVideoGenerateModes.effectiveChainPrevTail(series, shot);
    }

    /** 同场没有上一镜时，参考图模式不衔接落幅。首尾帧模式不受此限制。 */
    private boolean isFirstShotInChapter(DramaShot shot) {
        return previousSameChapterShot(shot) == null;
    }

    /** 项目级模式变更后，让所有镜头重新继承项目设置。 */
    public void inheritSeriesVideoSettings(Long seriesId) {
        if (seriesId == null) {
            return;
        }
        shotMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DramaShot>()
                .eq(DramaShot::getSeriesId, seriesId)
                .set(DramaShot::getVideoGenerateMode, null)
                .set(DramaShot::getVideoChainPrevTail, null));
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
            throw new BizException(ErrorCode.BAD_REQUEST, "请先准备角色/场景设定图，用于自动画开场静帧");
        }
        AiModel image = requireBoundImageModel(series);
        boolean useI2i = imageSupportsI2i(image);
        String prevTailKey = useI2i ? resolveStillPrevTailKey(shot) : null;
        List<DramaStoryboardCompiler.ActiveReference> active = new ArrayList<>();
        // i2i：本镜全部参考图都作为图生图输入；t2i 降级时不写 Picture，避免无图却点名参考。
        if (useI2i) {
            for (DramaAsset asset : refs) {
                active.add(toActive(asset));
            }
            if (StringUtils.hasText(prevTailKey)) {
                active.add(DramaStoryboardCompiler.prevTailStillReference());
            }
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
        if (useI2i) {
            List<GenerateSubmitReq.ReferenceInput> inputs = new ArrayList<>();
            for (DramaAsset asset : refs) {
                GenerateSubmitReq.ReferenceInput input = new GenerateSubmitReq.ReferenceInput();
                input.setType(AssetKind.IMAGE);
                if (StringUtils.hasText(asset.getImageStorageKey())) {
                    input.setStorageKey(asset.getImageStorageKey());
                } else if (asset.getImageAssetId() != null) {
                    input.setAssetId(asset.getImageAssetId());
                } else {
                    throw new BizException(ErrorCode.BAD_REQUEST, asset.getName() + "缺少可用设定图，无法图生图");
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
        extra.put("stillMode", useI2i ? GenerateMode.I2I : GenerateMode.T2I);
        GenerateTaskResp task = generateService.submit(req, extra);
        shot.setGenTaskId(task.getId());
        shotMapper.updateById(shot);
        touchSeries(series.getId());
        log.info("drama opening still shot {} series={} mode={} refs={} continueToVideo={} stillChainPrevTail={} task={}",
                shot.getId(), series.getId(), useI2i ? GenerateMode.I2I : GenerateMode.T2I,
                useI2i ? req.getReferences().size() : 0, continueToVideo,
                StringUtils.hasText(prevTailKey), task.getId());
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
                return doGenerateShot(shot.getId(), false);
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

    /**
     * 图像模型是否可用于开场静帧图生图。已识别适配协议时以协议能力为准（如 gpt-image-2），
     * 与 {@link cn.aigc.dit.server.service.GenerateService#modeSupported} 一致。
     */
    private static boolean imageSupportsI2i(AiModel model) {
        if (model == null) {
            return false;
        }
        String adapter = resolvedAdapter(model);
        return GenerateService.modeSupported(
                model.getCapabilities(), adapter, ModelCategory.IMAGE, GenerateMode.I2I);
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
