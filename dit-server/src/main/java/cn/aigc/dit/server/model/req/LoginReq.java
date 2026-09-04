package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "登录请求")
public class LoginReq {

    @NotBlank(message = "账号不能为空")
    @Schema(description = "用户名或邮箱；管理员账号为 admin", example = "admin")
    private String account;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度为 6-64 位")
    @Schema(example = "12345678")
    private String password;
}
