package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新建创作会话")
public class CreationSessionCreateReq {
    @Size(max = 256)
    private String title;
    private String category;
}
