package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class AdminGenTaskService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int EXPORT_LIMIT = 5000;
    static final int DEFAULT_REQUEST_HOURS = 24;
    static final int DEFAULT_QUEUE_DAYS = 7;
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GenTaskMapper genTaskMapper;
    private final CreditService creditService;
    private final GenerateTaskPublisher publisher;
    private final ObjectProvider<GenerateWorker> generateWorker;
    private final GenerateProperties generateProperties;
    @Qualifier("generateKickExecutor")
    private final Executor generateKickExecutor;

    public PageQueryResp<AdminGenTaskResp> pageRequests(int pageNum, int pageSize, String keyword, String status, Integer hours) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        OffsetDateTime since = now.minusHours(clampHours(hours));
        return page(pageNum, pageSize, since, null, normalizeStatus(status, false), normalizeKeyword(keyword));
    }

    public byte[] exportRequestsCsv(String keyword, String status, Integer hours) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        OffsetDateTime since = now.minusHours(clampHours(hours));
        String statusFilter = normalizeStatus(status, false);
        String kw = normalizeKeyword(keyword);
        List<AdminGenTaskRow> rows = genTaskMapper.selectAdminPage(since, null, statusFilter, kw, EXPORT_LIMIT, 0);
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("请求编号,用户,模型,来源,时间,耗时,积分,状态,失败原因\n");
        for (AdminGenTaskRow row : rows) {
            AdminGenTaskResp item = toResp(row, now);
            sb.append(csv(item.getRequestNo())).append(',')
                    .append(csv(item.getUserName())).append(',')
                    .append(csv(item.getModelName())).append(',')
                    .append(csv(item.getSource())).append(',')
                    .append(csv(formatTime(item.getCreatedAt()))).append(',')
                    .append(csv(formatDuration(item.getDurationSeconds()))).append(',')
                    .append(item.getCreditCost() == null ? 0 : item.getCreditCost()).append(',')
                    .append(csv(item.getStatusLabel())).append(',')
                    .append(csv(item.getErrorMessage())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public PageQueryResp<AdminGenTaskResp> pageQueue(int pageNum, int pageSize, String status, Integer days) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        OffsetDateTime since = now.minusDays(clampDays(days));
        return page(pageNum, pageSize, since, null, normalizeStatus(status, true), null);
    }

    public AdminQueueSummaryResp summary() {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        OffsetDateTime dayAgo = now.minusHours(24);
        OffsetDateTime weekAgo = now.minusDays(DEFAULT_QUEUE_DAYS);

        long running = nz(genTaskMapper.selectCount(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.RUNNING)));
        long pending = nz(genTaskMapper.selectCount(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.PENDING)));

        Map<String, Long> last24h = toCountMap(genTaskMapper.countByStatusBetween(dayAgo, now.plusSeconds(1)));
        long succeeded = last24h.getOrDefault(TaskStatus.SUCCEEDED, 0L);
        long failed = last24h.getOrDefault(TaskStatus.FAILED, 0L);
        long finished = succeeded + failed;
        Double avg = genTaskMapper.avgDurationSecondsBetween(dayAgo, now.plusSeconds(1));

        Map<String, Long> week = toCountMap(genTaskMapper.countByStatusBetween(weekAgo, now.plusSeconds(1)));
        long tabPending = week.getOrDefault(TaskStatus.PENDING, 0L);
        long tabRunning = week.getOrDefault(TaskStatus.RUNNING, 0L);
        long tabSucceeded = week.getOrDefault(TaskStatus.SUCCEEDED, 0L);
        long tabFailed = week.getOrDefault(TaskStatus.FAILED, 0L);
        long tabCancelled = week.getOrDefault(TaskStatus.CANCELLED, 0L);
        long tabAll = tabPending + tabRunning + tabSucceeded + tabFailed + tabCancelled;

        AdminQueueSummaryResp resp = new AdminQueueSummaryResp();
        resp.setRunning(running);
        resp.setPending(pending);
        resp.setAvgDurationSeconds(avg == null ? 0.0 : round1(avg));
        resp.setSuccessRate(finished == 0 ? 0.0 : round1(succeeded * 100.0 / finished));
        resp.setSchedulerRunning(isSchedulerRunning());
        resp.setMaxConcurrent(Math.max(1, generateProperties.getWorker().getMaxConcurrent()));
        Map<String, Long> tabs = new LinkedHashMap<>();
        tabs.put("ALL", tabAll);
        tabs.put(TaskStatus.PENDING, tabPending);
        tabs.put(TaskStatus.RUNNING, tabRunning);
        tabs.put(TaskStatus.SUCCEEDED, tabSucceeded);
        tabs.put(TaskStatus.FAILED, tabFailed);
        tabs.put(TaskStatus.CANCELLED, tabCancelled);
        resp.setTabs(tabs);
        return resp;
    }

    public AdminGenTaskResp cancel(Long id) {
        GenerateWorker worker = generateWorker.getIfAvailable();
        Holder<AdminGenTaskResp> result = new Holder<>();
        Runnable action = () -> result.value = cancelLocked(id, worker);
        if (worker != null) {
            worker.withTaskLock(id, action);
        } else {
            action.run();
        }
        if (result.value == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return result.value;
    }

    public AdminGenTaskResp retry(Long id) {
        GenerateWorker worker = generateWorker.getIfAvailable();
        Holder<AdminGenTaskResp> result = new Holder<>();
        Runnable action = () -> result.value = retryLocked(id);
        if (worker != null) {
            worker.withTaskLock(id, action);
        } else {
            action.run();
        }
        if (result.value == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        afterCommit(() -> {
            publisher.publish(id);
            GenerateWorker available = generateWorker.getIfAvailable();
            if (available != null) {
                generateKickExecutor.execute(() -> available.kick(id));
            }
        });
        return result.value;
    }

    private AdminGenTaskResp cancelLocked(Long id, GenerateWorker worker) {
        GenTask task = requireTask(id);
        if (!TaskStatus.PENDING.equals(task.getStatus()) && !TaskStatus.RUNNING.equals(task.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅等待中或运行中的任务可取消");
        }
        if (worker != null) {
            worker.cancelProviderJob(task);
        }
        genTaskMapper.markCancelled(id);
        refundIfHeld(task, "管理员取消退还积分");
        GenTask latest = requireTask(id);
        return toResp(toRow(latest), OffsetDateTime.now());
    }

    private AdminGenTaskResp retryLocked(Long id) {
        GenTask task = requireTask(id);
        if (!TaskStatus.FAILED.equals(task.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅失败任务可重试");
        }
        long cost = task.getCreditCost() == null ? 0L : task.getCreditCost();
        String refId = String.valueOf(task.getId());
        if (cost > 0 && creditService.netByRef(refId) >= 0) {
            creditService.change(task.getUserId(), -cost, CreditReason.GENERATE, refId,
                    "管理员重试重新扣费");
        }
        Map<String, Object> params = task.getParams() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(task.getParams());
        params.remove("submitRetries");
        params.remove("retryAfterEpochMs");
        task.setParams(params);
        genTaskMapper.updateById(task);
        genTaskMapper.resetForRetry(id);
        GenTask latest = requireTask(id);
        return toResp(toRow(latest), OffsetDateTime.now());
    }

    private void refundIfHeld(GenTask task, String remark) {
        if (task.getCreditCost() == null || task.getCreditCost() <= 0) {
            return;
        }
        String refId = String.valueOf(task.getId());
        long net = creditService.netByRef(refId);
        if (net >= 0) {
            return;
        }
        creditService.change(task.getUserId(), -net, CreditReason.REFUND, refId, remark);
    }

    private PageQueryResp<AdminGenTaskResp> page(
            int pageNum, int pageSize, OffsetDateTime since, OffsetDateTime until, String status, String keyword) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        long total = genTaskMapper.countAdminPage(since, until, status, keyword);
        int offset = (safePage - 1) * safeSize;
        List<AdminGenTaskRow> rows = total <= 0
                ? List.of()
                : genTaskMapper.selectAdminPage(since, until, status, keyword, safeSize, offset);
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        List<AdminGenTaskResp> result = new ArrayList<>(rows.size());
        for (AdminGenTaskRow row : rows) {
            result.add(toResp(row, now));
        }
        return PageQueryResp.of(safePage, safeSize, total, result);
    }

    private GenTask requireTask(Long id) {
        GenTask task = genTaskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    private boolean isSchedulerRunning() {
        if (!generateProperties.getWorker().isEnabled()) {
            return false;
        }
        GenerateWorker worker = generateWorker.getIfAvailable();
        return worker != null && worker.heartbeatFresh();
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    static AdminGenTaskResp toResp(AdminGenTaskRow row, OffsetDateTime now) {
        AdminGenTaskResp resp = new AdminGenTaskResp();
        if (row == null || row.getId() == null) {
            return resp;
        }
        resp.setId(row.getId());
        resp.setRequestNo("REQ-" + row.getId());
        resp.setTaskNo("TASK-" + row.getId());
        resp.setCategory(row.getCategory());
        resp.setCategoryLabel(categoryLabel(row.getCategory()));
        resp.setUserName(row.getUserName());
        resp.setModelName(row.getModelName());
        resp.setSource(StringUtils.hasText(row.getSource()) ? row.getSource() : "快速创作");
        resp.setStatus(row.getStatus());
        resp.setStatusLabel(statusLabel(row.getStatus()));
        resp.setProgress(row.getProgress() == null ? 0 : row.getProgress());
        resp.setErrorMessage(row.getErrorMessage());
        resp.setCreditCost(row.getCreditCost() == null ? 0L : row.getCreditCost());
        resp.setCreatedAt(row.getCreatedAt());
        resp.setStartedAt(row.getStartedAt());
        resp.setFinishedAt(row.getFinishedAt());
        resp.setDurationSeconds(durationSeconds(row, now));
        resp.setWaitSeconds(waitSeconds(row, now));
        return resp;
    }

    static Double durationSeconds(AdminGenTaskRow row, OffsetDateTime now) {
        if (row == null || row.getCreatedAt() == null) {
            return null;
        }
        OffsetDateTime end = row.getFinishedAt();
        if (end == null && TaskStatus.RUNNING.equals(row.getStatus())) {
            end = now;
        }
        if (end == null) {
            return null;
        }
        double seconds = Duration.between(row.getCreatedAt(), end).toMillis() / 1000.0;
        return round1(Math.max(0, seconds));
    }

    static Double waitSeconds(AdminGenTaskRow row, OffsetDateTime now) {
        if (row == null || row.getCreatedAt() == null) {
            return 0.0;
        }
        OffsetDateTime end;
        if (row.getStartedAt() != null) {
            end = row.getStartedAt();
        } else if (TaskStatus.PENDING.equals(row.getStatus()) || TaskStatus.RUNNING.equals(row.getStatus())) {
            end = now;
        } else if (row.getFinishedAt() != null) {
            end = row.getFinishedAt();
        } else {
            return 0.0;
        }
        double seconds = Duration.between(row.getCreatedAt(), end).toMillis() / 1000.0;
        return round1(Math.max(0, seconds));
    }

    static String categoryLabel(String category) {
        if (ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return "视频生成";
        }
        if (ModelCategory.AUDIO.equalsIgnoreCase(category)) {
            return "语音合成";
        }
        if (ModelCategory.IMAGE.equalsIgnoreCase(category)) {
            return "图像生成";
        }
        return category == null ? "" : category;
    }

    static String statusLabel(String status) {
        if (TaskStatus.RUNNING.equals(status)) {
            return "运行中";
        }
        if (TaskStatus.PENDING.equals(status)) {
            return "等待中";
        }
        if (TaskStatus.SUCCEEDED.equals(status)) {
            return "成功";
        }
        if (TaskStatus.FAILED.equals(status)) {
            return "失败";
        }
        if (TaskStatus.CANCELLED.equals(status)) {
            return "已取消";
        }
        return status == null ? "" : status;
    }

    static int clampHours(Integer hours) {
        if (hours == null) {
            return DEFAULT_REQUEST_HOURS;
        }
        return Math.min(720, Math.max(1, hours));
    }

    static int clampDays(Integer days) {
        if (days == null) {
            return DEFAULT_QUEUE_DAYS;
        }
        return Math.min(90, Math.max(1, days));
    }

    static String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return keyword.trim();
    }

    static String normalizeStatus(String status, boolean allowAll) {
        if (!StringUtils.hasText(status) || "ALL".equalsIgnoreCase(status.trim())) {
            return allowAll ? null : null;
        }
        String value = status.trim().toUpperCase();
        if (TaskStatus.PENDING.equals(value)
                || TaskStatus.RUNNING.equals(value)
                || TaskStatus.SUCCEEDED.equals(value)
                || TaskStatus.FAILED.equals(value)
                || TaskStatus.CANCELLED.equals(value)) {
            return value;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "不支持的任务状态");
    }

    static String formatDuration(Double seconds) {
        if (seconds == null) {
            return "--";
        }
        if (seconds < 60) {
            return round1(seconds) + "s";
        }
        long total = Math.round(seconds);
        return (total / 60) + "m " + (total % 60) + "s";
    }

    static String formatTime(OffsetDateTime time) {
        if (time == null) {
            return "";
        }
        return time.atZoneSameInstant(ZONE).toLocalDateTime().format(CSV_TIME);
    }

    static String csv(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    static AdminGenTaskRow toRow(GenTask task) {
        AdminGenTaskRow row = new AdminGenTaskRow();
        row.setId(task.getId());
        row.setUserId(task.getUserId());
        row.setCategory(task.getCategory());
        row.setStatus(task.getStatus());
        row.setProgress(task.getProgress());
        row.setErrorMessage(task.getErrorMessage());
        row.setSource(task.getParams() == null || task.getParams().get("source") == null
                ? "快速创作"
                : String.valueOf(task.getParams().get("source")));
        row.setCreditCost(task.getCreditCost());
        row.setCreatedAt(task.getCreatedAt());
        row.setStartedAt(task.getStartedAt());
        row.setFinishedAt(task.getFinishedAt());
        return row;
    }

    static Map<String, Long> toCountMap(List<NamedCount> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        if (rows == null) {
            return map;
        }
        for (NamedCount row : rows) {
            if (row != null && row.getName() != null) {
                map.put(row.getName(), row.getCnt() == null ? 0L : row.getCnt());
            }
        }
        return map;
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static final class Holder<T> {
        private T value;
    }
}
