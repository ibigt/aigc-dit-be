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
    private String engine;
    private String sourceText;
    private Map<String, Object> script;
    @Schema(description = "乙的 DramaTextService 是否已合入")
    private Boolean textServiceAvailable;
    @Schema(description = "是否可点「生成 AI 剧本」（服务已合入且项目文本模型可用）")
    private Boolean canGenerate;
    private Long inheritedFromEpisodeId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
