package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "分镜页全局绑定视频模型")
public class DramaVideoModelBindReq {

    @NotNull(message = "请选择视频模型")
    @Schema(description = "已启用 category=video 且支持 r2v 的模型 id")
    private Long videoModelId;
}
