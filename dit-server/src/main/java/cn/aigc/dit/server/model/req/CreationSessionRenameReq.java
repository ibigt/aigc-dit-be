package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "重命名创作会话")
public class CreationSessionRenameReq {
    @NotBlank
    @Size(max = 256)
    private String title;
}
