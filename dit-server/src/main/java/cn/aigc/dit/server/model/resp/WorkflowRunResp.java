package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "工作流运行进度")
public class WorkflowRunResp {
    private Long id;
    private Long workflowId;
    private String status;
    private Integer progress;
    private Integer total;
    private String errorMessage;
    private List<StepResp> steps;
    private OffsetDateTime createdAt;
    private OffsetDateTime finishedAt;

    @Data
    public static class StepResp {
        private String nodeId;
        private Long runId;
        private String title;
        private String stepType;
        private String status;
        private Integer progress;
        private Long creditCost;
        private String resolvedPrompt;
        private Long taskId;
        private String errorMessage;
        private List<OutputResp> outputs;
    }

    @Data
    public static class OutputResp {
        private String type;
        private String storageKey;
        private String url;
        private String value;
        private String posterUrl;
        private Map<String, Object> metadata;
    }
}
