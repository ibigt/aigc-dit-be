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
    @Schema(description = "模型标识，供前端按品牌目录兜底匹配图标")
    private String modelKey;
    @Schema(description = "品牌图标，与模型管理保持一致")
    private String logo;
    @Schema(description = "该模型支持的分镜时长（秒），已与 5–15 求交")
    private List<Integer> durations;
    @Schema(description = "该模型支持的成片清晰度，已与 480P/720P/1080P/2K 求交")
    private List<String> resolutions;
    private Boolean supportsNativeAudio;
    private Boolean supportsAudioReference;
    private Integer maxAudioReferences;
    @Schema(description = "单镜最多可携带的参考图张数，与厂商校验一致")
    private Integer maxImageReferences;
    private Boolean supportsAudioOnlyReference;
    private String audioMode;
    @Schema(description = "是否支持参考生视频")
    private Boolean supportsR2v;
    @Schema(description = "是否支持首尾帧")
    private Boolean supportsI2v;
}
