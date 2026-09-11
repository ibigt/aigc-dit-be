package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Schema(description = "漫剧系列（项目）")
public class DramaSeriesResp {

    private Long id;
    private String name;
    private String styleKey;
    private String styleName;
    private String styleCover;
    @Schema(description = "主体页第一个已出图场景的对象 key；没有则为空")
    private String coverKey;
    @Schema(description = "项目列表封面，来自第一个已出图场景")
    private String coverUrl;
    private String aspectRatio;
    private String defaultResolution;
    private Integer defaultDurationSec;
    private Integer plannedEpisodeCount;
    private Long textModelId;
    private String textModelName;
    @Schema(description = "绑定的文本模型当前是否可用于 LLM（有绑定且 Gateway 判定可用）")
    private Boolean textModelUsable;
    private Long imageModelId;
    private String imageModelName;
    private String imageAdapterKey;
    private Long videoModelId;
    private String videoModelName;
    private String videoAdapterKey;
    private Integer episodeCount;
    private DramaEpisodeResp latestEpisode;
    private List<DramaEpisodeResp> episodes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
