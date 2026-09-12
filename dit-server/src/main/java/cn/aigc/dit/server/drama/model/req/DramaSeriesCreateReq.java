package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新建漫剧系列（同时创建第 1 集）")
public class DramaSeriesCreateReq {

    @NotBlank(message = "系列名称不能为空")
    @Size(max = 128)
    @Schema(description = "系列名称", example = "侦探社")
    private String name;

    @Size(max = 64)
    @Schema(description = "风格键，当前仅 anime-comic", example = "anime-comic")
    private String styleKey;

    @Schema(description = "画幅：16:9 / 9:16 / 1:1", example = "16:9")
    private String aspectRatio;

    @Schema(description = "默认清晰度：480P / 720P", example = "720P")
    private String defaultResolution;

    @Schema(description = "默认单镜时长秒：5 / 10 / 15", example = "15")
    private Integer defaultDurationSec;

    @Schema(description = "规划集数 1–99", example = "5")
    private Integer plannedEpisodeCount;

    @Size(max = 128)
    @Schema(description = "第 1 集名称，默认「第 1 集」")
    private String firstEpisodeName;

    @Schema(description = "绑定的文本模型 id；有已启用文本模型时必选")
    private Long textModelId;
}
