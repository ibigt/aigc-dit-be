package cn.aigc.dit.server.mapper.stat;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ModelObservabilityRow {
    private Long modelId;
    private String modelName;
    private String provider;
    private String category;
    private Long total;
    private Long succeeded;
    private Long failed;
    private Long cancelled;
    private Long running;
    private Long pending;
    private Long creditCost;
    private Double avgDurationSeconds;
    private Double p95DurationSeconds;
    private Double avgWaitSeconds;
    private OffsetDateTime lastCalledAt;
}
