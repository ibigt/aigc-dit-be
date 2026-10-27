package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "提交生成。前端不传厂商接口名；mode 由后端推导为 t2i/i2i/t2v/r2v/i2v/t2a。")
public class GenerateSubmitReq {

    @NotNull(message = "模型不能为空")
    private Long modelId;

    private Long sessionId;

    @Size(max = 4096)
    private String prompt;

    @NotBlank(message = "类型不能为空")
    @Schema(description = "image / video / audio")
    private String category;

    private String ratio;
    private String resolution;
    private String quality;

    @Min(1)
    @Max(30)
    private Integer durationSec;

    @Min(0)
    @Max(3600)
    @Schema(description = "参考视频合计秒数（前端读浏览器元数据估算）；仅在服务端探测不到素材时长时兜底，计费取与服务端实测的较大值")
    private Integer referenceVideoSeconds;

    @Size(max = 128)
    @Schema(description = "语音音色标识；category=audio 时使用")
    private String voiceId;

    @DecimalMin("0.25")
    @DecimalMax("4.0")
    @Schema(description = "语速倍率，默认 1.0")
    private Double speed;

    @Size(max = 16)
    @Schema(description = "音频格式：mp3 / wav / aac / flac / opus / pcm")
    private String audioFormat;

    @Min(1)
    @Max(4)
    private Integer copies = 1;

    @Valid
    @Size(max = 15, message = "参考素材最多选择15个")
    private List<ReferenceInput> references;

    @Valid
    private FrameInput frames;

    @Data
    public static class ReferenceInput {
        @Schema(description = "image / video / audio；是否允许 audio 由所选模型的 supportsAudioReference 决定")
        private String type;
        private String storageKey;
        private Long assetId;
    }

    @Data
    public static class FrameInput {
        private String firstFrameKey;
        private String lastFrameKey;
    }
}
