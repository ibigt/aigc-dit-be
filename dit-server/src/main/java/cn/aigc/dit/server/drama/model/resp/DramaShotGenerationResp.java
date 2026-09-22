package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "漫剧分镜生成历史记录")
public class DramaShotGenerationResp {

    private Long id;
    private Long shotId;
    private String videoKey;
    private String videoUrl;
    private String videoPosterKey;
    private String videoPosterUrl;
    private String videoTailKey;
    private String videoTailUrl;
    private Long genTaskId;
    private String source;
    private String label;
    private Boolean selected;
    private OffsetDateTime createdAt;
}
