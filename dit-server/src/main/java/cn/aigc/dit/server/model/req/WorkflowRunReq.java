package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "运行当前画布。只执行图像、视频、音频生成节点，并按连线顺序提交生成任务。")
public class WorkflowRunReq {

    @Schema(description = "已保存的工作流；空白画布可省略")
    private Long workflowId;

    @Schema(description = "前一次运行 ID；服务端验证归属与输入版本，用于未保存画布的单节点重试")
    private Long previousRunId;

    private Long modelId;

    @Schema(description = "只运行该节点及其上游生成节点")
    private String nodeId;

    @Schema(description = "true = 全部重新生成（不复用历史产物，所有生成/剪辑步骤都真跑）；缺省或 false = 复用参数未变节点的既有产物")
    private Boolean forceRegenerate;

    @NotNull(message = "画布不能为空")
    private Map<String, Object> graph;
}
