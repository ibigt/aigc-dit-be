package cn.aigc.dit.server.model.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "当前用户资料")
public class UserProfileResp {
    private Long id;
    private String account;
    private String name;
    private String email;
    private String role;
    private String roleLabel;
    @JsonProperty("isAdmin")
    private boolean admin;
    private String status;
    private Long creditBalance;
}
