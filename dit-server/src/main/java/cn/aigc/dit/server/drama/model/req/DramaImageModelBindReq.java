package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "资产页全局绑定图像模型")
public class DramaImageModelBindReq {

    @NotNull(message = "请选择图像模型")
    @Schema(description = "已启用 category=image 的模型 id")
    private Long imageModelId;
}
