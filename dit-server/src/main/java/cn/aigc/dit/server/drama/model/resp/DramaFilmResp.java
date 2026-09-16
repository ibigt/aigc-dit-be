package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "本集成片")
public class DramaFilmResp {

    private Long episodeId;
    @Schema(description = "成片预览地址")
    private String filmUrl;
    @Schema(description = "成片封面")
    private String posterUrl;
    @Schema(description = "指纹与当前镜头 video_key 不一致")
    private Boolean stale;
    @Schema(description = "本集全部分镜均已出片")
    private Boolean complete;
    @Schema(description = "成片是否包含可展示的中文字幕")
    private Boolean hasCaptions;
    private List<Integer> missingShotIndexes = new ArrayList<>();
    private Integer shotCount;
    private Integer readyShotCount;
    private OffsetDateTime assembledAt;
    private List<DramaFilmShotResp> shots = new ArrayList<>();
}
