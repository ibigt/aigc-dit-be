package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
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
import cn.aigc.dit.server.drama.model.resp.DramaShotGenerateResp;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaShotBatchGenerateServiceTest {

    @Mock
    private DramaShotBatchJobMapper jobMapper;
    @Mock
    private DramaShotBatchItemMapper itemMapper;
    @Mock
    private DramaShotMapper shotMapper;
    @Mock
    private DramaEpisodeMapper episodeMapper;
    @Mock
    private DramaSeriesMapper seriesMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private DramaStoryboardService storyboardService;

    private DramaShotBatchGenerateService service;
    private final List<DramaShotBatchJob> storedJobs = new ArrayList<>();
    private final List<DramaShotBatchItem> storedItems = new ArrayList<>();
    private final List<DramaShot> storedShots = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(500);

    @BeforeEach
    void setUp() {
        service = new DramaShotBatchGenerateService(
                jobMapper, itemMapper, shotMapper, episodeMapper, seriesMapper, genTaskMapper, storyboardService);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        storedJobs.clear();
        storedItems.clear();
        storedShots.clear();

        org.mockito.Mockito.lenient().when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        org.mockito.Mockito.lenient().when(seriesMapper.selectById(21L)).thenReturn(ownedSeries());
        org.mockito.Mockito.lenient().when(jobMapper.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.lenient().when(jobMapper.insert(any(DramaShotBatchJob.class))).thenAnswer(inv -> {
            DramaShotBatchJob job = inv.getArgument(0);
            job.setId(ids.incrementAndGet());
            storedJobs.add(job);
            return 1;
        });
        org.mockito.Mockito.lenient().when(jobMapper.selectById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return storedJobs.stream().filter(j -> id.equals(j.getId())).findFirst().orElse(null);
        });
        org.mockito.Mockito.lenient().when(jobMapper.updateById(any(DramaShotBatchJob.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(itemMapper.insert(any(DramaShotBatchItem.class))).thenAnswer(inv -> {
            DramaShotBatchItem item = inv.getArgument(0);
            item.setId(ids.incrementAndGet());
            storedItems.add(item);
            return 1;
        });
        org.mockito.Mockito.lenient().when(itemMapper.selectById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return storedItems.stream().filter(i -> id.equals(i.getId())).findFirst().orElse(null);
        });
        org.mockito.Mockito.lenient().when(itemMapper.selectList(any())).thenAnswer(inv -> List.copyOf(storedItems));
        org.mockito.Mockito.lenient().when(itemMapper.updateById(any(DramaShotBatchItem.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(shotMapper.selectById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return storedShots.stream().filter(s -> id.equals(s.getId())).findFirst().orElse(null);
        });
        org.mockito.Mockito.lenient().when(shotMapper.selectList(any())).thenAnswer(inv -> List.copyOf(storedShots));
        org.mockito.Mockito.lenient().when(shotMapper.selectBatchIds(any())).thenAnswer(inv -> List.copyOf(storedShots));
        org.mockito.Mockito.lenient().when(shotMapper.updateById(any(DramaShot.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(storyboardService.generateShotForBatch(anyLong())).thenAnswer(inv -> {
            Long shotId = inv.getArgument(0);
            DramaShot shot = storedShots.stream().filter(s -> shotId.equals(s.getId())).findFirst().orElseThrow();
            long taskId = ids.incrementAndGet();
            shot.setGenTaskId(taskId);
            DramaShotGenerateResp resp = new DramaShotGenerateResp();
            GenerateTaskResp task = new GenerateTaskResp();
            task.setId(taskId);
            task.setStatus(TaskStatusPending());
            resp.setTask(task);
            return resp;
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void allowsCrossChapterSelection() {
        storedShots.add(shot(201L, 1, 90L));
        storedShots.add(shot(202L, 2, 91L));

        DramaShotBatchGenerateReq req = new DramaShotBatchGenerateReq();
        req.setShotIds(List.of(201L, 202L));
        req.setVideoGenerateMode("r2v");
        req.setChainPrevTail(false);

        DramaShotBatchGenerateResp resp = service.create(31L, req);

        assertTrue(Boolean.TRUE.equals(resp.getConcurrent()));
        assertEquals(DramaShotBatchGenerateService.STATUS_RUNNING, resp.getStatus());
        verify(storyboardService, times(2)).generateShotForBatch(anyLong());
    }

    @Test
    void rejectsShotWithoutChapter() {
        storedShots.add(shot(201L, 1, null));

        DramaShotBatchGenerateReq req = new DramaShotBatchGenerateReq();
        req.setShotIds(List.of(201L));
        req.setVideoGenerateMode("r2v");
        req.setChainPrevTail(false);

        BizException error = assertThrows(BizException.class, () -> service.create(31L, req));
        assertTrue(error.getMessage().contains("场次"));
        verify(storyboardService, never()).generateShotForBatch(anyLong());
    }

    @Test
    void concurrentR2vStartsAllPending() {
        storedShots.add(shot(201L, 1, 90L));
        storedShots.add(shot(202L, 2, 90L));

        DramaShotBatchGenerateReq req = new DramaShotBatchGenerateReq();
        req.setShotIds(List.of(201L, 202L));
        req.setVideoGenerateMode("r2v");
        req.setChainPrevTail(false);

        DramaShotBatchGenerateResp resp = service.create(31L, req);

        assertTrue(Boolean.TRUE.equals(resp.getConcurrent()));
        assertEquals(DramaShotBatchGenerateService.STATUS_RUNNING, resp.getStatus());
        verify(storyboardService, times(2)).generateShotForBatch(anyLong());
        assertEquals(2, storedItems.stream()
                .filter(i -> DramaShotBatchGenerateService.STATUS_RUNNING.equals(i.getStatus()))
                .count());
    }

    @Test
    void sequentialChainOnlyStartsFirst() {
        storedShots.add(shot(201L, 1, 90L));
        storedShots.add(shot(202L, 2, 90L));

        DramaShotBatchGenerateReq req = new DramaShotBatchGenerateReq();
        req.setShotIds(List.of(201L, 202L));
        req.setVideoGenerateMode("r2v");
        req.setChainPrevTail(true);

        DramaShotBatchGenerateResp resp = service.create(31L, req);

        assertFalse(Boolean.TRUE.equals(resp.getConcurrent()));
        verify(storyboardService, times(1)).generateShotForBatch(201L);
        verify(storyboardService, never()).generateShotForBatch(202L);
        assertEquals(DramaShotBatchGenerateService.STATUS_RUNNING, storedItems.get(0).getStatus());
        assertEquals(DramaShotBatchGenerateService.STATUS_PENDING, storedItems.get(1).getStatus());
    }

    @Test
    void i2vAutoIsConcurrentAndWritesI2vOnFirstShot() {
        DramaShot first = shot(201L, 1, 90L);
        first.setFirstFrameKey("old.png");
        first.setFirstFrameOrigin("upload");
        storedShots.add(first);
        storedShots.add(shot(202L, 2, 90L));

        DramaShotBatchGenerateReq req = new DramaShotBatchGenerateReq();
        req.setShotIds(List.of(201L, 202L));
        req.setVideoGenerateMode("i2v");
        req.setI2vFirstFrameMode("auto");

        DramaShotBatchGenerateResp resp = service.create(31L, req);

        assertTrue(Boolean.TRUE.equals(resp.getConcurrent()));
        assertEquals(DramaVideoGenerateModes.I2V, first.getVideoGenerateMode());
        assertEquals(null, first.getFirstFrameKey());
        ArgumentCaptor<Long> shotIds = ArgumentCaptor.forClass(Long.class);
        verify(storyboardService, times(2)).generateShotForBatch(shotIds.capture());
        assertTrue(shotIds.getAllValues().containsAll(List.of(201L, 202L)));
    }

    @Test
    void sequentialFailsWhenPrevOutsideBatchHasNoTail() {
        storedShots.add(shot(201L, 1, 90L));
        DramaShot second = shot(202L, 2, 90L);
        storedShots.add(second);

        DramaShotBatchGenerateReq req = new DramaShotBatchGenerateReq();
        req.setShotIds(List.of(202L));
        req.setVideoGenerateMode("r2v");
        req.setChainPrevTail(true);

        BizException error = assertThrows(BizException.class, () -> service.create(31L, req));
        assertTrue(error.getMessage().contains("落幅"));
        verify(storyboardService, never()).generateShotForBatch(anyLong());
    }

    @Test
    void isShotBatchLockedWhenRunningItem() {
        DramaShotBatchJob job = new DramaShotBatchJob();
        job.setId(801L);
        job.setEpisodeId(31L);
        job.setUserId(7L);
        job.setStatus(DramaShotBatchGenerateService.STATUS_RUNNING);
        storedJobs.add(job);
        DramaShotBatchItem item = new DramaShotBatchItem();
        item.setId(802L);
        item.setJobId(801L);
        item.setShotId(201L);
        item.setShotIndex(1);
        item.setStatus(DramaShotBatchGenerateService.STATUS_PENDING);
        storedItems.add(item);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(jobMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.isShotBatchLocked(201L));
        BizException error = assertThrows(BizException.class, () -> service.assertNotBatchLocked(201L));
        assertTrue(error.getMessage().contains("批量生成"));
    }

    @Test
    void resolveConcurrentMatrix() {
        assertTrue(DramaShotBatchGenerateService.resolveConcurrent("r2v", false, null));
        assertFalse(DramaShotBatchGenerateService.resolveConcurrent("r2v", true, null));
        assertTrue(DramaShotBatchGenerateService.resolveConcurrent("i2v", false, "auto"));
        assertFalse(DramaShotBatchGenerateService.resolveConcurrent("i2v", false, "prev_tail"));
    }

    private static String TaskStatusPending() {
        return DramaShotBatchGenerateService.STATUS_PENDING;
    }

    private static DramaShot shot(Long id, int index, Long chapterId) {
        DramaShot shot = new DramaShot();
        shot.setId(id);
        shot.setEpisodeId(31L);
        shot.setSeriesId(21L);
        shot.setUserId(7L);
        shot.setChapterId(chapterId);
        shot.setShotIndex(index);
        shot.setTitle("镜 " + index);
        shot.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        return shot;
    }

    private static DramaEpisode ownedEpisode() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setUserId(7L);
        episode.setSeriesId(21L);
        episode.setStatus(DramaContract.STATUS_LOCKED);
        return episode;
    }

    private static DramaSeries ownedSeries() {
        DramaSeries series = new DramaSeries();
        series.setId(21L);
        series.setUserId(7L);
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        return series;
    }
}
