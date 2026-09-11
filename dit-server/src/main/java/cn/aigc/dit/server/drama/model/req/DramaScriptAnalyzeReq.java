package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "剧本分析配置")
public class DramaScriptAnalyzeReq {

    @NotBlank(message = "请粘贴剧本或梗概")
    @Size(max = 200_000, message = "剧本文本过长")
    private String sourceText;

    @Min(value = 1, message = "集数至少为 1")
    @Max(value = 100, message = "集数不能超过 100")
    private Integer episodeCount = 1;

    @Min(value = 5, message = "单集时长至少为 5 秒")
    @Max(value = 60, message = "单集时长不能超过 60 秒")
    private Integer episodeDurationSec = 15;

    @Min(value = 1, message = "目标分镜数至少为 1")
    @Max(value = 100, message = "目标分镜数不能超过 100")
    private Integer shotsPerEpisode = 8;

    @Size(max = 2_000, message = "补充要求过长")
    private String requirements;
}
