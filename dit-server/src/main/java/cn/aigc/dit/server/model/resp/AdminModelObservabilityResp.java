package cn.aigc.dit.server.model.resp;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdminModelObservabilityResp {
    private long total;
    private long succeeded;
    private long failed;
    private long running;
    private long pending;
    private long creditCost;
    private double successRate;
    private double avgDurationSeconds;
    private List<ModelMetric> models = new ArrayList<>();

    @Data
    public static class ModelMetric {
        private Long modelId;
        private String modelName;
        private String provider;
        private String category;
        private String categoryLabel;
        private long total;
        private long succeeded;
        private long failed;
        private long cancelled;
        private long running;
        private long pending;
        private long creditCost;
        private double successRate;
        private double avgDurationSeconds;
        private double p95DurationSeconds;
        private double avgWaitSeconds;
        private String lastCalledAt;
    }
}
