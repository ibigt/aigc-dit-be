package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "更新漫剧系列基础信息")
public class DramaSeriesUpdateReq {

    @Size(max = 128)
    private String name;

    @Size(max = 64)
    @Schema(description = "风格键：anime-comic / live-realism / anime-wuxia / costume-comic / republican-era / cyberpunk / sci-fi / rural")
    private String styleKey;

    @Schema(description = "画幅：21:9 / 16:9 / 4:3 / 1:1 / 3:4 / 9:16")
    private String aspectRatio;

    @Schema(description = "默认清晰度：480P / 720P / 1080P / 2K")
    private String defaultResolution;

    @Schema(description = "默认单镜时长秒：5–15")
    private Integer defaultDurationSec;

    @Schema(description = "规划集数 1–99")
    private Integer plannedEpisodeCount;

    @Schema(description = "更换绑定的文本模型；不传则保持原绑定")
    private Long textModelId;
}
