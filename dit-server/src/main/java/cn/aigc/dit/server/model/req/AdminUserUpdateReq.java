package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "管理端更新用户")
public class AdminUserUpdateReq {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 128, message = "姓名过长")
    private String name;

    @Size(max = 64, message = "登录账号过长")
    private String account;

    @Size(max = 128)
    private String email;

    @Schema(description = "ADMIN 或 CREATOR")
    private String role;

    @Schema(description = "ENABLED 或 DISABLED")
    private String status;

    @Schema(description = "积分余额；变更会记入积分流水")
    private Long creditBalance;
}
