package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.entity.DramaShotBatchItem;
import cn.aigc.dit.server.drama.entity.DramaShotBatchJob;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotBatchItemMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotBatchJobMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.model.req.DramaShotBatchGenerateReq;
import cn.aigc.dit.server.drama.model.resp.DramaShotBatchGenerateResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotBatchItemResp;
import cn.aigc.dit.server.drama.model.resp.DramaShotGenerateResp;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaShotBatchGenerateService {

    public static final String STATUS_PENDING = TaskStatus.PENDING;
    public static final String STATUS_RUNNING = TaskStatus.RUNNING;
    public static final String STATUS_SUCCEEDED = TaskStatus.SUCCEEDED;
    public static final String STATUS_FAILED = TaskStatus.FAILED;
    public static final String STATUS_CANCELLED = TaskStatus.CANCELLED;

    public static final String I2V_FRAME_AUTO = "auto";
    public static final String I2V_FRAME_PREV_TAIL = "prev_tail";

    private static final String LOCK_MESSAGE = "该镜正在批量生成中";

    private final DramaShotBatchJobMapper jobMapper;
    private final DramaShotBatchItemMapper itemMapper;
    private final DramaShotMapper shotMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaSeriesMapper seriesMapper;
    private final GenTaskMapper genTaskMapper;
    private final DramaStoryboardService storyboardService;

    @Autowired
    private ObjectProvider<DramaShotBatchGenerateService> self;

    private DramaShotBatchGenerateService ops() {
        DramaShotBatchGenerateService bean = self == null ? null : self.getIfAvailable();
        return bean != null ? bean : this;
    }

    @Transactional
    public DramaShotBatchGenerateResp create(Long episodeId, DramaShotBatchGenerateReq req) {
        DramaEpisode episode = requireEpisode(episodeId);
        DramaSeries series = requireOwnedSeries(episode.getSeriesId());
        assertNoRunningJob(episodeId);

        String mode = DramaVideoGenerateModes.canonical(req.getVideoGenerateMode());
        boolean chainPrevTail = Boolean.TRUE.equals(req.getChainPrevTail());
        String i2vFrame = normalizeI2vFrameMode(mode, req.getI2vFirstFrameMode());
        boolean firstShotAutoStill = req.getFirstShotAutoStill() == null || Boolean.TRUE.equals(req.getFirstShotAutoStill());
        boolean concurrent = resolveConcurrent(mode, chainPrevTail, i2vFrame);

        List<DramaShot> selected = resolveSelectedShots(episodeId, req.getShotIds());
        assertSameChapter(selected);
        if (!concurrent) {
            assertSequentialDeps(selected, chainPrevTail || I2V_FRAME_PREV_TAIL.equals(i2vFrame));
        }

        applyBatchSettings(selected, mode, chainPrevTail, i2vFrame, firstShotAutoStill);

        DramaShotBatchJob job = new DramaShotBatchJob();
        job.setEpisodeId(episodeId);
        job.setSeriesId(series.getId());
        job.setUserId(UserContext.requireUserId());
        job.setStatus(STATUS_RUNNING);
        job.setVideoGenerateMode(mode);
        job.setChainPrevTail(chainPrevTail);
        job.setI2vFirstFrameMode(i2vFrame);
        job.setFirstShotAutoStill(firstShotAutoStill);
        job.setConcurrent(concurrent);
        jobMapper.insert(job);

        for (DramaShot shot : selected) {
            DramaShotBatchItem item = new DramaShotBatchItem();
            item.setJobId(job.getId());
            item.setShotId(shot.getId());
            item.setShotIndex(shot.getShotIndex());
            item.setStatus(STATUS_PENDING);
            itemMapper.insert(item);
        }

        // 出片走独立事务；须在 create 提交后再 kick，避免 generateShot 抛错把本事务标成 rollback-only
        Long jobId = job.getId();
        Runnable kick = () -> {
            try {
                ops().advance(jobId);
            } catch (RuntimeException e) {
                log.warn("batch kick after create failed job={}: {}", jobId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kick.run();
                }
            });
        } else {
            kick.run();
        }
        return toResp(job, listItems(jobId));
    }

    @Transactional
    public DramaShotBatchGenerateResp getJob(Long episodeId, Long jobId) {
        requireEpisode(episodeId);
        DramaShotBatchJob job = requireJob(jobId, episodeId);
        if (STATUS_RUNNING.equals(job.getStatus()) || STATUS_PENDING.equals(job.getStatus())) {
            advance(jobId);
            job = requireJob(jobId, episodeId);
        }
        return toResp(job, listItems(jobId));
    }

    @Transactional(readOnly = true)
    public DramaShotBatchGenerateResp findActive(Long episodeId) {
        requireEpisode(episodeId);
        DramaShotBatchJob job = jobMapper.selectOne(new LambdaQueryWrapper<DramaShotBatchJob>()
                .eq(DramaShotBatchJob::getEpisodeId, episodeId)
                .eq(DramaShotBatchJob::getStatus, STATUS_RUNNING)
                .orderByDesc(DramaShotBatchJob::getId)
                .last("LIMIT 1"));
        if (job == null) {
            return null;
        }
        return toResp(job, listItems(job.getId()));
    }

    @Transactional(readOnly = true)
    public Long findActiveJobId(Long episodeId) {
        if (episodeId == null) {
            return null;
        }
        DramaShotBatchJob job = jobMapper.selectOne(new LambdaQueryWrapper<DramaShotBatchJob>()
                .eq(DramaShotBatchJob::getEpisodeId, episodeId)
                .eq(DramaShotBatchJob::getStatus, STATUS_RUNNING)
                .orderByDesc(DramaShotBatchJob::getId)
                .last("LIMIT 1"));
        return job == null ? null : job.getId();
    }

    /** 镜头是否属于 RUNNING 批次且条目未终态（禁止改/删/单镜再出片）。 */
    @Transactional(readOnly = true)
    public boolean isShotBatchLocked(Long shotId) {
        if (shotId == null) {
            return false;
        }
        List<DramaShotBatchItem> items = itemMapper.selectList(new LambdaQueryWrapper<DramaShotBatchItem>()
                .eq(DramaShotBatchItem::getShotId, shotId)
                .in(DramaShotBatchItem::getStatus, List.of(STATUS_PENDING, STATUS_RUNNING)));
        if (items.isEmpty()) {
            return false;
        }
        Set<Long> jobIds = items.stream().map(DramaShotBatchItem::getJobId).collect(Collectors.toSet());
        Long count = jobMapper.selectCount(new LambdaQueryWrapper<DramaShotBatchJob>()
                .in(DramaShotBatchJob::getId, jobIds)
                .eq(DramaShotBatchJob::getStatus, STATUS_RUNNING));
        return count != null && count > 0;
    }

    public void assertNotBatchLocked(Long shotId) {
        if (isShotBatchLocked(shotId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, LOCK_MESSAGE);
        }
    }

    @Transactional
    public void advance(Long jobId) {
        if (jobId == null) {
            return;
        }
        DramaShotBatchJob job = jobMapper.selectById(jobId);
        if (job == null || !STATUS_RUNNING.equals(job.getStatus())) {
            return;
        }
        List<DramaShotBatchItem> items = new ArrayList<>(listItems(jobId));
        items.sort(Comparator.comparing(DramaShotBatchItem::getShotIndex, Comparator.nullsLast(Integer::compareTo)));

        Map<Long, DramaShot> shots = loadShots(items);
        boolean failed = false;
        String failMessage = null;

        for (DramaShotBatchItem item : items) {
            if (!STATUS_RUNNING.equals(item.getStatus())) {
                continue;
            }
            try {
                ops().syncItemIsolated(item.getId());
            } catch (BizException e) {
                markItemFailed(item, e.getMessage());
                failed = true;
                failMessage = e.getMessage();
                break;
            } catch (RuntimeException e) {
                log.warn("batch item sync failed job={} item={} shot={}", jobId, item.getId(), item.getShotId(), e);
                markItemFailed(item, e.getMessage());
                failed = true;
                failMessage = e.getMessage();
                break;
            }
        }

        if (failed) {
            failJob(job, new ArrayList<>(listItems(jobId)), failMessage);
            return;
        }

        // 刷新后重新读取
        items = new ArrayList<>(listItems(jobId));
        items.sort(Comparator.comparing(DramaShotBatchItem::getShotIndex, Comparator.nullsLast(Integer::compareTo)));
        shots = loadShots(items);

        if (Boolean.TRUE.equals(job.getConcurrent())) {
            for (DramaShotBatchItem item : items) {
                if (!STATUS_PENDING.equals(item.getStatus())) {
                    continue;
                }
                try {
                    ops().startItemIsolated(item.getId());
                } catch (BizException e) {
                    markItemFailed(item, e.getMessage());
                    failJob(job, listItems(jobId), e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    log.warn("batch start failed job={} shot={}", jobId, item.getShotId(), e);
                    markItemFailed(item, e.getMessage());
                    failJob(job, listItems(jobId), e.getMessage());
                    return;
                }
            }
        } else {
            DramaShotBatchItem next = findNextSequential(items);
            if (next != null && canStartSequential(job, items, next, shots)) {
                try {
                    ops().startItemIsolated(next.getId());
                } catch (BizException e) {
                    markItemFailed(next, e.getMessage());
                    failJob(job, listItems(jobId), e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    log.warn("batch sequential start failed job={} shot={}", jobId, next.getShotId(), e);
                    markItemFailed(next, e.getMessage());
                    failJob(job, listItems(jobId), e.getMessage());
                    return;
                }
            }
        }

        items = listItems(jobId);
        finalizeIfDone(job, items);
    }

    /** 独立事务启动一镜，避免 generateShot 抛错污染 advance / create 外层事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startItemIsolated(Long itemId) {
        DramaShotBatchItem item = itemMapper.selectById(itemId);
        if (item == null || !STATUS_PENDING.equals(item.getStatus())) {
            return;
        }
        startItem(item, shotMapper.selectById(item.getShotId()));
    }

    /** 独立事务同步一镜进度。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncItemIsolated(Long itemId) {
        DramaShotBatchItem item = itemMapper.selectById(itemId);
        if (item == null || !STATUS_RUNNING.equals(item.getStatus())) {
            return;
        }
        syncRunningItem(item, shotMapper.selectById(item.getShotId()));
    }

    @Transactional
    public void advanceRunningJobs() {
        List<DramaShotBatchJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<DramaShotBatchJob>()
                .eq(DramaShotBatchJob::getStatus, STATUS_RUNNING)
                .orderByAsc(DramaShotBatchJob::getId)
                .last("LIMIT 20"));
        for (DramaShotBatchJob job : jobs) {
            LoginUser previous = UserContext.get();
            UserContext.set(new LoginUser(
                    job.getUserId(), "batch-worker", null, "batch-worker", LoginUser.ROLE_CREATOR));
            try {
                advance(job.getId());
            } catch (RuntimeException e) {
                log.warn("batch advance job {} failed: {}", job.getId(), e.getMessage());
            } finally {
                if (previous != null) {
                    UserContext.set(previous);
                } else {
                    UserContext.clear();
                }
            }
        }
    }

    private void syncRunningItem(DramaShotBatchItem item, DramaShot shot) {
        if (shot == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分镜已不存在");
        }
        Long taskId = shot.getGenTaskId() != null ? shot.getGenTaskId() : item.getGenTaskId();
        if (taskId == null) {
            return;
        }
        DramaShotGenerateResp progress = storyboardService.getGenerateTask(shot.getId(), taskId);
        shot = shotMapper.selectById(shot.getId());
        GenerateTaskResp task = progress == null ? null : progress.getTask();
        if (task == null) {
            GenTask live = genTaskMapper.selectById(taskId);
            if (live == null) {
                return;
            }
            if (DramaStoryboardService.isGeneratingStatus(live.getStatus())) {
                if (!Objects.equals(item.getGenTaskId(), live.getId())) {
                    item.setGenTaskId(live.getId());
                    itemMapper.updateById(item);
                }
                return;
            }
            if (TaskStatus.FAILED.equals(live.getStatus()) || TaskStatus.CANCELLED.equals(live.getStatus())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        StringUtils.hasText(live.getErrorMessage()) ? live.getErrorMessage() : "出片失败");
            }
            if (TaskStatus.SUCCEEDED.equals(live.getStatus()) && StringUtils.hasText(shot.getVideoKey())) {
                markItemSucceeded(item, live.getId());
            }
            return;
        }
        String status = task.getStatus();
        Long liveTaskId = shot.getGenTaskId() != null ? shot.getGenTaskId() : task.getId();
        if (!Objects.equals(item.getGenTaskId(), liveTaskId)) {
            item.setGenTaskId(liveTaskId);
            itemMapper.updateById(item);
        }
        if (DramaStoryboardService.isGeneratingStatus(status)
                || (progress.getShot() != null && Boolean.TRUE.equals(progress.getShot().getGenerating()))) {
            return;
        }
        if (TaskStatus.FAILED.equals(status) || TaskStatus.CANCELLED.equals(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    StringUtils.hasText(task.getErrorMessage()) ? task.getErrorMessage() : "出片失败");
        }
        if (TaskStatus.SUCCEEDED.equals(status) && StringUtils.hasText(shot.getVideoKey())) {
            // 成片已就绪即记成功；串行链式在 canStartSequential 再等落幅
            markItemSucceeded(item, liveTaskId);
        }
    }

    private void startItem(DramaShotBatchItem item, DramaShot shot) {
        if (shot == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分镜已不存在");
        }
        DramaShotGenerateResp resp = storyboardService.generateShotForBatch(shot.getId());
        Long taskId = resp != null && resp.getTask() != null
                ? resp.getTask().getId()
                : (resp != null && resp.getShot() != null ? resp.getShot().getGenTaskId() : null);
        item.setStatus(STATUS_RUNNING);
        item.setGenTaskId(taskId);
        item.setStartedAt(OffsetDateTime.now());
        item.setErrorMessage(null);
        itemMapper.updateById(item);
    }

    private DramaShotBatchItem findNextSequential(List<DramaShotBatchItem> items) {
        for (DramaShotBatchItem item : items) {
            if (STATUS_PENDING.equals(item.getStatus())) {
                return item;
            }
            if (STATUS_RUNNING.equals(item.getStatus())) {
                return null;
            }
        }
        return null;
    }

    private boolean canStartSequential(
            DramaShotBatchJob job,
            List<DramaShotBatchItem> items,
            DramaShotBatchItem next,
            Map<Long, DramaShot> shots) {
        DramaShotBatchItem prevItem = previousItem(items, next);
        if (prevItem == null) {
            return true;
        }
        if (!STATUS_SUCCEEDED.equals(prevItem.getStatus())) {
            return false;
        }
        boolean needsTail = Boolean.TRUE.equals(job.getChainPrevTail())
                || I2V_FRAME_PREV_TAIL.equals(job.getI2vFirstFrameMode());
        if (!needsTail) {
            return true;
        }
        DramaShot prevShot = shots.get(prevItem.getShotId());
        return prevShot != null && StringUtils.hasText(prevShot.getVideoTailKey());
    }

    private static DramaShotBatchItem previousItem(List<DramaShotBatchItem> items, DramaShotBatchItem next) {
        DramaShotBatchItem prev = null;
        for (DramaShotBatchItem item : items) {
            if (item.getId().equals(next.getId())) {
                break;
            }
            prev = item;
        }
        return prev;
    }

    private void applyBatchSettings(
            List<DramaShot> shots,
            String mode,
            boolean chainPrevTail,
            String i2vFrame,
            boolean firstShotAutoStill) {
        Set<Long> chapterIds = shots.stream().map(DramaShot::getChapterId).collect(Collectors.toSet());
        Map<Long, Integer> chapterMinIndex = new HashMap<>();
        for (Long chapterId : chapterIds) {
            List<DramaShot> chapterShots = shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                    .eq(DramaShot::getChapterId, chapterId)
                    .orderByAsc(DramaShot::getShotIndex));
            int min = chapterShots.stream()
                    .map(DramaShot::getShotIndex)
                    .filter(Objects::nonNull)
                    .min(Integer::compareTo)
                    .orElse(Integer.MAX_VALUE);
            chapterMinIndex.put(chapterId, min);
        }
        for (DramaShot shot : shots) {
            boolean firstInChapter = Objects.equals(shot.getShotIndex(), chapterMinIndex.get(shot.getChapterId()));
            shot.setVideoGenerateMode(mode);
            if (DramaVideoGenerateModes.isI2v(mode)) {
                shot.setVideoChainPrevTail(false);
                shot.setFirstFrameKey(null);
                shot.setFirstFrameOrigin(null);
                // prev-tail 不预挂载；场首 + auto / firstShotAutoStill 走静帧链路，保持无首帧即可
                if (firstInChapter
                        && I2V_FRAME_PREV_TAIL.equals(i2vFrame)
                        && !firstShotAutoStill) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "场内第一镜引用上一镜落幅时，请开启「场内第一镜自动生成首帧」");
                }
            } else {
                shot.setVideoChainPrevTail(chainPrevTail && !firstInChapter);
            }
            shotMapper.updateById(shot);
        }
    }

    private List<DramaShot> resolveSelectedShots(Long episodeId, List<Long> shotIds) {
        if (shotIds == null || shotIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择至少一个分镜");
        }
        LinkedHashMap<Long, DramaShot> byId = new LinkedHashMap<>();
        for (Long shotId : shotIds) {
            if (shotId == null || byId.containsKey(shotId)) {
                continue;
            }
            DramaShot shot = shotMapper.selectById(shotId);
            if (shot == null || !episodeId.equals(shot.getEpisodeId())
                    || !shot.getUserId().equals(UserContext.requireUserId())) {
                throw new BizException(ErrorCode.NOT_FOUND, "分镜不存在");
            }
            byId.put(shotId, shot);
        }
        if (byId.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择至少一个分镜");
        }
        List<DramaShot> selected = new ArrayList<>(byId.values());
        selected.sort(Comparator.comparing(DramaShot::getShotIndex, Comparator.nullsLast(Integer::compareTo)));
        return selected;
    }

    private void assertSameChapter(List<DramaShot> shots) {
        Long chapterId = null;
        for (DramaShot shot : shots) {
            if (shot.getChapterId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "分镜缺少场次信息");
            }
            if (chapterId == null) {
                chapterId = shot.getChapterId();
            } else if (!chapterId.equals(shot.getChapterId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不能跨场次选择分镜");
            }
        }
    }

    private void assertSequentialDeps(List<DramaShot> selected, boolean needsPrevTail) {
        if (!needsPrevTail) {
            return;
        }
        Set<Long> selectedIds = selected.stream().map(DramaShot::getId).collect(Collectors.toSet());
        Map<Long, DramaShot> episodeShots = listEpisodeShots(selected.get(0).getEpisodeId()).stream()
                .collect(Collectors.toMap(DramaShot::getId, s -> s, (a, b) -> a, LinkedHashMap::new));
        for (DramaShot shot : selected) {
            DramaShot prev = previousSameChapter(shot, episodeShots);
            if (prev == null) {
                continue;
            }
            if (selectedIds.contains(prev.getId())) {
                continue;
            }
            if (StringUtils.hasText(prev.getVideoTailKey()) || StringUtils.hasText(prev.getVideoKey())) {
                continue;
            }
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "镜 " + shot.getShotIndex() + " 依赖的同场上一镜尚无落幅，请先出片或将其一并加入批量");
        }
    }

    private List<DramaShot> listEpisodeShots(Long episodeId) {
        return shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getEpisodeId, episodeId)
                .orderByAsc(DramaShot::getShotIndex));
    }

    private static DramaShot previousSameChapter(DramaShot shot, Map<Long, DramaShot> episodeShots) {
        if (shot == null || shot.getChapterId() == null || shot.getShotIndex() == null) {
            return null;
        }
        DramaShot prev = null;
        for (DramaShot item : episodeShots.values()) {
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

    private void assertNoRunningJob(Long episodeId) {
        Long count = jobMapper.selectCount(new LambdaQueryWrapper<DramaShotBatchJob>()
                .eq(DramaShotBatchJob::getEpisodeId, episodeId)
                .eq(DramaShotBatchJob::getStatus, STATUS_RUNNING));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "本集已有批量生成任务进行中");
        }
    }

    static boolean resolveConcurrent(String mode, boolean chainPrevTail, String i2vFrame) {
        if (DramaVideoGenerateModes.isR2v(mode)) {
            return !chainPrevTail;
        }
        return I2V_FRAME_AUTO.equals(i2vFrame);
    }

    static String normalizeI2vFrameMode(String mode, String raw) {
        if (!DramaVideoGenerateModes.isI2v(mode)) {
            return null;
        }
        if (!StringUtils.hasText(raw)) {
            return I2V_FRAME_AUTO;
        }
        String value = raw.trim().toLowerCase().replace('-', '_');
        if ("prev_tail".equals(value) || "prevtail".equals(value)) {
            return I2V_FRAME_PREV_TAIL;
        }
        if (I2V_FRAME_AUTO.equals(value)) {
            return I2V_FRAME_AUTO;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "首帧方式仅支持 auto 或 prev-tail");
    }

    private void markItemSucceeded(DramaShotBatchItem item, Long taskId) {
        item.setStatus(STATUS_SUCCEEDED);
        item.setGenTaskId(taskId);
        item.setFinishedAt(OffsetDateTime.now());
        item.setErrorMessage(null);
        itemMapper.updateById(item);
    }

    private void markItemFailed(DramaShotBatchItem item, String message) {
        item.setStatus(STATUS_FAILED);
        item.setErrorMessage(truncate(message));
        item.setFinishedAt(OffsetDateTime.now());
        itemMapper.updateById(item);
    }

    private void failJob(DramaShotBatchJob job, List<DramaShotBatchItem> items, String message) {
        for (DramaShotBatchItem item : items) {
            if (STATUS_PENDING.equals(item.getStatus())) {
                item.setStatus(STATUS_CANCELLED);
                item.setFinishedAt(OffsetDateTime.now());
                itemMapper.updateById(item);
            }
        }
        job.setStatus(STATUS_FAILED);
        job.setErrorMessage(truncate(message));
        jobMapper.updateById(job);
    }

    private void finalizeIfDone(DramaShotBatchJob job, List<DramaShotBatchItem> items) {
        boolean anyRunning = false;
        boolean anyPending = false;
        boolean anyFailed = false;
        for (DramaShotBatchItem item : items) {
            if (STATUS_RUNNING.equals(item.getStatus())) {
                anyRunning = true;
            } else if (STATUS_PENDING.equals(item.getStatus())) {
                anyPending = true;
            } else if (STATUS_FAILED.equals(item.getStatus())) {
                anyFailed = true;
            }
        }
        if (anyRunning || anyPending) {
            return;
        }
        if (anyFailed) {
            job.setStatus(STATUS_FAILED);
            if (!StringUtils.hasText(job.getErrorMessage())) {
                job.setErrorMessage("批量生成失败");
            }
        } else {
            job.setStatus(STATUS_SUCCEEDED);
            job.setErrorMessage(null);
        }
        jobMapper.updateById(job);
    }

    private Map<Long, DramaShot> loadShots(List<DramaShotBatchItem> items) {
        Set<Long> ids = new HashSet<>();
        for (DramaShotBatchItem item : items) {
            ids.add(item.getShotId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, DramaShot> map = new HashMap<>();
        for (DramaShot shot : shotMapper.selectBatchIds(ids)) {
            map.put(shot.getId(), shot);
        }
        return map;
    }

    private List<DramaShotBatchItem> listItems(Long jobId) {
        return itemMapper.selectList(new LambdaQueryWrapper<DramaShotBatchItem>()
                .eq(DramaShotBatchItem::getJobId, jobId)
                .orderByAsc(DramaShotBatchItem::getShotIndex));
    }

    private DramaShotBatchJob requireJob(Long jobId, Long episodeId) {
        DramaShotBatchJob job = jobMapper.selectById(jobId);
        if (job == null || !episodeId.equals(job.getEpisodeId())
                || !job.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "批量任务不存在");
        }
        return job;
    }

    private DramaEpisode requireEpisode(Long episodeId) {
        DramaEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null || !episode.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        return episode;
    }

    private DramaSeries requireOwnedSeries(Long seriesId) {
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series == null || !series.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "系列不存在");
        }
        return series;
    }

    private DramaShotBatchGenerateResp toResp(DramaShotBatchJob job, List<DramaShotBatchItem> items) {
        DramaShotBatchGenerateResp resp = new DramaShotBatchGenerateResp();
        resp.setId(job.getId());
        resp.setEpisodeId(job.getEpisodeId());
        resp.setSeriesId(job.getSeriesId());
        resp.setStatus(job.getStatus());
        resp.setVideoGenerateMode(job.getVideoGenerateMode());
        resp.setChainPrevTail(job.getChainPrevTail());
        resp.setI2vFirstFrameMode(job.getI2vFirstFrameMode());
        resp.setFirstShotAutoStill(job.getFirstShotAutoStill());
        resp.setConcurrent(job.getConcurrent());
        resp.setErrorMessage(job.getErrorMessage());
        resp.setCreatedAt(job.getCreatedAt());
        resp.setUpdatedAt(job.getUpdatedAt());
        List<DramaShotBatchItemResp> itemResps = new ArrayList<>();
        for (DramaShotBatchItem item : items) {
            itemResps.add(toItemResp(item));
        }
        resp.setItems(itemResps);
        return resp;
    }

    private static DramaShotBatchItemResp toItemResp(DramaShotBatchItem item) {
        DramaShotBatchItemResp resp = new DramaShotBatchItemResp();
        resp.setId(item.getId());
        resp.setJobId(item.getJobId());
        resp.setShotId(item.getShotId());
        resp.setShotIndex(item.getShotIndex());
        resp.setStatus(item.getStatus());
        resp.setGenTaskId(item.getGenTaskId());
        resp.setErrorMessage(item.getErrorMessage());
        resp.setStartedAt(item.getStartedAt());
        resp.setFinishedAt(item.getFinishedAt());
        return resp;
    }

    private static String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String text = message.trim();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
