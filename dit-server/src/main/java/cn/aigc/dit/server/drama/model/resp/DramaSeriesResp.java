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
    private String aspectRatio;
    private String defaultResolution;
    private Integer defaultDurationSec;
    private Integer plannedEpisodeCount;
    private Long textModelId;
    private String textModelName;
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
