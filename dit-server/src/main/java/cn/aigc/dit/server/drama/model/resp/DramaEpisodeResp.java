package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Schema(description = "漫剧剧集")
public class DramaEpisodeResp {

    private Long id;
    private Long seriesId;
    private Integer episodeNumber;
    private String name;
    private String status;
    private String sourceText;
    private Map<String, Object> script;
    private Long inheritedFromEpisodeId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
