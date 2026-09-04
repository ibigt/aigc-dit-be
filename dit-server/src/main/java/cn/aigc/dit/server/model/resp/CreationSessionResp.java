package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Schema(description = "创作会话")
public class CreationSessionResp {
    private Long id;
    private String title;
    private String category;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<GenerateTaskResp> tasks;
}
