package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "管理端创建用户")
public class AdminUserCreateReq {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 128, message = "姓名过长")
    private String name;

    @NotBlank(message = "登录账号不能为空")
    @Size(max = 64, message = "登录账号过长")
    @Schema(description = "登录账号，建议使用邮箱")
    private String account;

    @Size(max = 128)
    private String email;

    @Schema(description = "ADMIN 或 CREATOR", example = "CREATOR")
    private String role;

    @Schema(description = "ENABLED 或 DISABLED")
    private String status;

    @Schema(description = "初始积分")
    private Long creditBalance;

    @Size(min = 6, max = 64, message = "密码长度为 6-64 位")
    @Schema(description = "可选；为空则生成临时密码")
    private String password;
}
