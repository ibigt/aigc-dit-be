package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "更新个人信息")
public class UpdateProfileReq {

    @Size(max = 64)
    private String name;

    @Email
    @Size(max = 128)
    private String email;
}
