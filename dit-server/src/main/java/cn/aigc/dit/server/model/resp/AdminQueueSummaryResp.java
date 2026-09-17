package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Schema(description = "管理端任务队列汇总")
public class AdminQueueSummaryResp {
    private long running;
    private long pending;
    private double avgDurationSeconds;
    private double successRate;
    private boolean schedulerRunning;
    private int maxConcurrent;
    private Map<String, Long> tabs = new LinkedHashMap<>();
}
