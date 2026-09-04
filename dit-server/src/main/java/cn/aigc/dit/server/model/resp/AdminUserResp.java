package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "管理端用户")
public class AdminUserResp {
    private Long id;
    private String name;
    private String account;
    private String email;
    private String role;
    private String roleLabel;
    private String status;
    private String statusLabel;
    private Long credits;
    private long requests;
    private OffsetDateTime lastSeen;
    private OffsetDateTime createdAt;
    @Schema(description = "仅创建时若系统生成密码则返回")
    private String temporaryPassword;
}
