package cn.aigc.dit.server.model.resp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "高级工作台工作流")
public class WorkflowResp {
    private Long id;
    private String title;
    private String description;
    private String type;
    private Long modelId;
    private String modelLabel;
    private String origin;
    private String demoKey;
    private String sourcePath;
    private Boolean builtin;
    private Integer nodeCount;
    private Map<String, Object> graph;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
