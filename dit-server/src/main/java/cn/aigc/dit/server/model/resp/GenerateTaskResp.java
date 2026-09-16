package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Schema(description = "生成任务")
public class GenerateTaskResp {
    private Long id;
    private Long sessionId;
    private Long modelId;
    private String modelName;
    private String category;
    private String mode;
    private String status;
    private Integer progress;
    private String errorMessage;
    private String prompt;
    private Map<String, Object> params;
    private Integer copies;
    private Long creditCost;
    private String outputStorageKey;
    private String posterKey;
    @Schema(description = "成片落幅对象 key（分镜出片抽末帧）")
    private String tailKey;
    private List<String> outputKeys;
    private List<CreationItemResp> creations;
    private OffsetDateTime createdAt;
    private OffsetDateTime finishedAt;
}
