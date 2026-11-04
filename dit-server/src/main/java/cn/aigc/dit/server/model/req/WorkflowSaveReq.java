package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "保存高级工作台工作流")
public class WorkflowSaveReq {

    @NotBlank(message = "名称不能为空")
    @Size(max = 128)
    private String title;

    @Size(max = 512)
    private String description;

    @Schema(description = "image / video / audio / import / template")
    private String type;

    private Long modelId;

    @Size(max = 128)
    private String origin;

    @Size(max = 64)
    private String demoKey;

    @Size(max = 256)
    private String sourcePath;

    @Schema(description = "画布 JSON：nodes、edges")
    private Map<String, Object> graph;
}
