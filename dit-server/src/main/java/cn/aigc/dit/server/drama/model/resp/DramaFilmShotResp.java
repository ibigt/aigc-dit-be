package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "成片页镜头清单项")
public class DramaFilmShotResp {

    private Long id;
    private Integer shotIndex;
    private String title;
    private Integer durationSec;
    private Boolean ready;
    private Boolean hasCaptions;
    private String videoUrl;
    private String videoPosterUrl;
}
