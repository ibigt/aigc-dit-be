package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "已启用视频模型（分镜页全局下拉；不含密钥）")
public class DramaVideoModelResp {

    private Long id;
    private String name;
    private String provider;
    private String adapterKey;
    @Schema(description = "该模型支持的分镜时长（秒），已与 5–15 求交")
    private List<Integer> durations;
    @Schema(description = "该模型支持的成片清晰度，已与 480P/720P 求交")
    private List<String> resolutions;
}
