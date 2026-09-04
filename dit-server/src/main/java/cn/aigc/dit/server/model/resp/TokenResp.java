package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录/注册令牌")
public class TokenResp {
    private String token;
    private String tokenType = "Bearer";
    private long expiresIn;
    private UserProfileResp user;
}
