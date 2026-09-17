package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateTaskPublisher;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.stat.AdminGenTaskRow;
import cn.aigc.dit.server.mapper.stat.NamedCount;
import cn.aigc.dit.server.model.resp.AdminGenTaskResp;
import cn.aigc.dit.server.model.resp.AdminQueueSummaryResp;
import cn.aigc.dit.server.worker.GenerateWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminGenTaskServiceTest {

    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private CreditService creditService;
    @Mock
    private GenerateTaskPublisher publisher;
    @Mock
    private ObjectProvider<GenerateWorker> generateWorker;
    @Mock
    private GenerateWorker worker;
    @Mock
    private Executor generateKickExecutor;

    private GenerateProperties generateProperties;
    private AdminGenTaskService service;

    @BeforeEach
    void setUp() {
        generateProperties = new GenerateProperties();
        generateProperties.getWorker().setEnabled(true);
        generateProperties.getWorker().setMaxConcurrent(8);
        service = new AdminGenTaskService(
                genTaskMapper, creditService, publisher, generateWorker, generateProperties, generateKickExecutor);
    }

    @Test
    void pageRequestsUses24hWindowAndJoinFields() {
        OffsetDateTime created = OffsetDateTime.now().minusMinutes(10);
        OffsetDateTime finished = created.plusSeconds(42);
        AdminGenTaskRow row = new AdminGenTaskRow();
        row.setId(12L);
        row.setUserName("林清");
        row.setModelName("MiniMax-H3");
        row.setSource("快速创作");
        row.setCategory(ModelCategory.VIDEO);
        row.setStatus(TaskStatus.SUCCEEDED);
        row.setCreditCost(80L);
        row.setCreatedAt(created);
        row.setFinishedAt(finished);
        when(genTaskMapper.countAdminPage(any(), isNull(), isNull(), eq("林清"))).thenReturn(1L);
        when(genTaskMapper.selectAdminPage(any(), isNull(), isNull(), eq("林清"), eq(20), eq(0)))
                .thenReturn(List.of(row));

        PageQueryResp<AdminGenTaskResp> page = service.pageRequests(1, 20, "林清", null, null);

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(genTaskMapper).countAdminPage(since.capture(), isNull(), isNull(), eq("林清"));
        assertTrue(since.getValue().isBefore(OffsetDateTime.now().minusHours(23)));
        assertTrue(since.getValue().isAfter(OffsetDateTime.now().minusHours(25)));
        assertEquals(1, page.getTotalNum());
        AdminGenTaskResp item = page.getResult().get(0);
        assertEquals("REQ-12", item.getRequestNo());
        assertEquals("TASK-12", item.getTaskNo());
        assertEquals("林清", item.getUserName());
        assertEquals("MiniMax-H3", item.getModelName());
        assertEquals("快速创作", item.getSource());
        assertEquals("成功", item.getStatusLabel());
        assertEquals("视频生成", item.getCategoryLabel());
        assertEquals(42.0, item.getDurationSeconds());
        assertEquals(80L, item.getCreditCost());
    }

    @Test
    void pageRequestsPaginatesAndFiltersStatus() {
        when(genTaskMapper.countAdminPage(any(), isNull(), eq(TaskStatus.FAILED), isNull())).thenReturn(45L);
        when(genTaskMapper.selectAdminPage(any(), isNull(), eq(TaskStatus.FAILED), isNull(), eq(20), eq(20)))
                .thenReturn(List.of());

        PageQueryResp<AdminGenTaskResp> page = service.pageRequests(2, 20, "  ", "FAILED", 24);
        assertEquals(2, page.getPageNum());
        assertEquals(45, page.getTotalNum());
        assertEquals(3, page.getPages());
    }

    @Test
    void pageRequestsPassesRequestNoKeywordThrough() {
        when(genTaskMapper.countAdminPage(any(), isNull(), isNull(), eq("REQ-12"))).thenReturn(0L);

        PageQueryResp<AdminGenTaskResp> page = service.pageRequests(1, 20, " REQ-12 ", null, 24);
        assertEquals(0, page.getTotalNum());
        assertTrue(page.getResult().isEmpty());
        verify(genTaskMapper, never()).selectAdminPage(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void pageQueueUses7DayWindowAndStatusFilter() {
        OffsetDateTime created = OffsetDateTime.now().minusHours(2);
        AdminGenTaskRow row = new AdminGenTaskRow();
        row.setId(21L);
        row.setUserName("林清");
        row.setModelName("MiniMax-H3");
        row.setCategory(ModelCategory.VIDEO);
        row.setStatus(TaskStatus.PENDING);
        row.setProgress(0);
        row.setCreditCost(40L);
        row.setCreatedAt(created);
        when(genTaskMapper.countAdminPage(any(), isNull(), eq(TaskStatus.PENDING), isNull())).thenReturn(1L);
        when(genTaskMapper.selectAdminPage(any(), isNull(), eq(TaskStatus.PENDING), isNull(), eq(20), eq(0)))
                .thenReturn(List.of(row));

        PageQueryResp<AdminGenTaskResp> page = service.pageQueue(1, 20, "PENDING", null);

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(genTaskMapper).countAdminPage(since.capture(), isNull(), eq(TaskStatus.PENDING), isNull());
        assertTrue(since.getValue().isBefore(OffsetDateTime.now().minusDays(6)));
        assertTrue(since.getValue().isAfter(OffsetDateTime.now().minusDays(8)));
        AdminGenTaskResp item = page.getResult().get(0);
        assertEquals("TASK-21", item.getTaskNo());
        assertEquals("等待中", item.getStatusLabel());
        assertEquals("视频生成", item.getCategoryLabel());
        assertEquals("林清", item.getUserName());
        assertEquals("MiniMax-H3", item.getModelName());
    }

    @Test
    void pageQueueAllDoesNotFilterStatus() {
        when(genTaskMapper.countAdminPage(any(), isNull(), isNull(), isNull())).thenReturn(0L);
        service.pageQueue(1, 20, "ALL", 7);
        verify(genTaskMapper).countAdminPage(any(), isNull(), isNull(), isNull());
    }

    @Test
    void pageRejectsUnknownStatus() {
        BizException ex = assertThrows(BizException.class, () -> service.pageRequests(1, 20, null, "DONE", 24));
        assertEquals("不支持的任务状态", ex.getMessage());
        verify(genTaskMapper, never()).countAdminPage(any(), any(), any(), any());
    }

    @Test
    void exportCsvIncludesBomAndEscapesComma() {
        AdminGenTaskRow row = new AdminGenTaskRow();
        row.setId(3L);
        row.setUserName("张,某");
        row.setModelName("Agnes");
        row.setSource("漫剧");
        row.setStatus(TaskStatus.FAILED);
        row.setCreditCost(12L);
        row.setCreatedAt(OffsetDateTime.parse("2026-09-17T03:28:42+08:00"));
        row.setErrorMessage("生成失败");
        when(genTaskMapper.selectAdminPage(any(), isNull(), isNull(), isNull(), eq(5000), eq(0)))
                .thenReturn(List.of(row));

        String csv = new String(service.exportRequestsCsv(null, null, 24), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF请求编号"));
        assertTrue(csv.contains("REQ-3"));
        assertTrue(csv.contains("\"张,某\""));
        assertTrue(csv.contains("失败"));
        assertTrue(csv.contains("生成失败"));
    }

    @Test
    void summaryExposesRunningPendingAndSchedulerHealth() {
        when(genTaskMapper.selectCount(any())).thenReturn(2L, 5L);
        NamedCount succeeded = new NamedCount();
        succeeded.setName(TaskStatus.SUCCEEDED);
        succeeded.setCnt(9L);
        NamedCount failed = new NamedCount();
        failed.setName(TaskStatus.FAILED);
        failed.setCnt(1L);
        when(genTaskMapper.countByStatusBetween(any(), any())).thenReturn(List.of(succeeded, failed));
        when(genTaskMapper.avgDurationSecondsBetween(any(), any())).thenReturn(18.44);
        when(generateWorker.getIfAvailable()).thenReturn(worker);
        when(worker.heartbeatFresh()).thenReturn(true);

        AdminQueueSummaryResp summary = service.summary();
        assertEquals(2L, summary.getRunning());
        assertEquals(5L, summary.getPending());
        assertEquals(18.4, summary.getAvgDurationSeconds());
        assertEquals(90.0, summary.getSuccessRate());
        assertTrue(summary.isSchedulerRunning());
        assertEquals(8, summary.getMaxConcurrent());
        assertEquals(10L, summary.getTabs().get("ALL"));
    }

    @Test
    void cancelPendingRefundsOnceAndStopsWorkerSuccessPath() {
        stubLock();
        GenTask pending = task(8L, TaskStatus.PENDING, 40L);
        GenTask cancelled = task(8L, TaskStatus.CANCELLED, 40L);
        cancelled.setProgress(0);
        when(genTaskMapper.selectById(8L)).thenReturn(pending, cancelled);
        when(creditService.netByRef("8")).thenReturn(-40L);

        AdminGenTaskResp resp = service.cancel(8L);
        assertEquals(TaskStatus.CANCELLED, resp.getStatus());
        verify(creditService).change(eq(2L), eq(40L), eq(CreditReason.REFUND), eq("8"), any());
        verify(worker).cancelProviderJob(pending);
        verify(genTaskMapper).markCancelled(8L);
        verify(publisher, never()).publish(any());
    }

    @Test
    void cancelRefundsWhenWorkerMissing() {
        when(generateWorker.getIfAvailable()).thenReturn(null);
        GenTask pending = task(8L, TaskStatus.PENDING, 40L);
        GenTask cancelled = task(8L, TaskStatus.CANCELLED, 40L);
        when(genTaskMapper.selectById(8L)).thenReturn(pending, cancelled);
        when(creditService.netByRef("8")).thenReturn(-40L);

        AdminGenTaskResp resp = service.cancel(8L);
        assertEquals(TaskStatus.CANCELLED, resp.getStatus());
        verify(creditService).change(eq(2L), eq(40L), eq(CreditReason.REFUND), eq("8"), any());
        verify(genTaskMapper).markCancelled(8L);
    }

    @Test
    void cancelDoesNotDoubleRefund() {
        stubLock();
        GenTask running = task(9L, TaskStatus.RUNNING, 40L);
        running.setProviderJobId("job-1");
        when(genTaskMapper.selectById(9L)).thenReturn(running);
        when(creditService.netByRef("9")).thenReturn(0L);

        service.cancel(9L);
        verify(creditService, never()).change(any(), anyLong(), any(), any(), any());
    }

    @Test
    void retryFailedRechargesAndRequeues() {
        stubLock();
        GenTask failed = task(11L, TaskStatus.FAILED, 25L);
        failed.setErrorMessage("超时");
        failed.setFinishedAt(OffsetDateTime.now());
        GenTask pending = task(11L, TaskStatus.PENDING, 25L);
        when(genTaskMapper.selectById(11L)).thenReturn(failed, pending);
        when(creditService.netByRef("11")).thenReturn(0L);
        when(generateWorker.getIfAvailable()).thenReturn(worker);
        AtomicBoolean kicked = new AtomicBoolean(false);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            kicked.set(true);
            return null;
        }).when(generateKickExecutor).execute(any());

        AdminGenTaskResp resp = service.retry(11L);
        assertEquals(TaskStatus.PENDING, resp.getStatus());
        verify(creditService).change(eq(2L), eq(-25L), eq(CreditReason.GENERATE), eq("11"), any());
        verify(publisher).publish(11L);
        verify(worker).kick(11L);
        assertTrue(kicked.get());
        verify(genTaskMapper).updateById(failed);
        verify(genTaskMapper).resetForRetry(11L);
    }

    @Test
    void retrySkipsChargeWhenCreditsStillHeld() {
        stubLock();
        GenTask failed = task(11L, TaskStatus.FAILED, 25L);
        GenTask pending = task(11L, TaskStatus.PENDING, 25L);
        when(genTaskMapper.selectById(11L)).thenReturn(failed, pending);
        when(creditService.netByRef("11")).thenReturn(-25L);
        when(generateWorker.getIfAvailable()).thenReturn(worker);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(generateKickExecutor).execute(any());

        service.retry(11L);
        verify(creditService, never()).change(any(), anyLong(), any(), any(), any());
        verify(publisher).publish(11L);
        verify(worker).kick(11L);
        verify(genTaskMapper).resetForRetry(11L);
    }

    @Test
    void retryRejectsWhenBalanceInsufficient() {
        stubLock();
        GenTask failed = task(13L, TaskStatus.FAILED, 99L);
        when(genTaskMapper.selectById(13L)).thenReturn(failed);
        when(creditService.netByRef("13")).thenReturn(0L);
        when(creditService.change(eq(2L), eq(-99L), eq(CreditReason.GENERATE), eq("13"), any()))
                .thenThrow(new BizException(400, "积分不足"));

        BizException ex = assertThrows(BizException.class, () -> service.retry(13L));
        assertEquals("积分不足", ex.getMessage());
        verify(publisher, never()).publish(any());
        verify(genTaskMapper, never()).updateById(any(GenTask.class));
        verify(genTaskMapper, never()).resetForRetry(any());
    }

    @Test
    void retryRejectsNonFailed() {
        stubLock();
        when(genTaskMapper.selectById(4L)).thenReturn(task(4L, TaskStatus.SUCCEEDED, 10L));
        assertThrows(BizException.class, () -> service.retry(4L));
    }

    @Test
    void cancelRejectsFinished() {
        stubLock();
        when(genTaskMapper.selectById(4L)).thenReturn(task(4L, TaskStatus.SUCCEEDED, 10L));
        assertThrows(BizException.class, () -> service.cancel(4L));
    }

    @Test
    void mapsDurationAndWait() {
        OffsetDateTime created = OffsetDateTime.parse("2026-09-17T01:00:00+08:00");
        AdminGenTaskRow running = new AdminGenTaskRow();
        running.setId(1L);
        running.setStatus(TaskStatus.RUNNING);
        running.setCreatedAt(created);
        running.setStartedAt(created.plusSeconds(8));
        AdminGenTaskResp resp = AdminGenTaskService.toResp(running, created.plusSeconds(20));
        assertEquals(20.0, resp.getDurationSeconds());
        assertEquals(8.0, resp.getWaitSeconds());
        assertEquals("运行中", resp.getStatusLabel());
        assertEquals("REQ-1", resp.getRequestNo());

        running.setStatus(TaskStatus.PENDING);
        running.setStartedAt(null);
        AdminGenTaskResp pending = AdminGenTaskService.toResp(running, created.plusSeconds(20));
        assertNull(pending.getDurationSeconds());

        running.setStatus(TaskStatus.SUCCEEDED);
        running.setFinishedAt(created.plusSeconds(42));
        AdminGenTaskResp done = AdminGenTaskService.toResp(running, created.plusSeconds(100));
        assertEquals(42.0, done.getDurationSeconds());
    }

    @Test
    void clampHoursDefaultsTo24() {
        assertEquals(24, AdminGenTaskService.clampHours(null));
        assertEquals(1, AdminGenTaskService.clampHours(0));
        assertEquals(720, AdminGenTaskService.clampHours(9999));
    }

    @Test
    void schedulerDownWhenWorkerMissing() {
        when(genTaskMapper.selectCount(any())).thenReturn(0L);
        when(genTaskMapper.countByStatusBetween(any(), any())).thenReturn(List.of());
        when(generateWorker.getIfAvailable()).thenReturn(null);
        assertFalse(service.summary().isSchedulerRunning());
    }

    private void stubLock() {
        when(generateWorker.getIfAvailable()).thenReturn(worker);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(worker).withTaskLock(any(), any());
    }

    private static GenTask task(Long id, String status, Long cost) {
        GenTask task = new GenTask();
        task.setId(id);
        task.setUserId(2L);
        task.setStatus(status);
        task.setCreditCost(cost);
        task.setProgress(10);
        task.setCategory(ModelCategory.IMAGE);
        task.setCreatedAt(OffsetDateTime.now().minusMinutes(1));
        task.setParams(Map.of("source", "快速创作"));
        return task;
    }
}
