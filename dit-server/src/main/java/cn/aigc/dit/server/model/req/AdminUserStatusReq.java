package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "启停用户")
public class AdminUserStatusReq {

    @NotBlank(message = "状态不能为空")
    @Schema(description = "ENABLED 或 DISABLED", example = "DISABLED")
    private String status;
}
