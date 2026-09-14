package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "提交生成。前端不传厂商接口名；mode 由后端按参考/首尾帧推导为 t2i/i2i/t2v/r2v/i2v。")
public class GenerateSubmitReq {

    @NotNull(message = "模型不能为空")
    private Long modelId;

    private Long sessionId;

    @Size(max = 4000)
    private String prompt;

    @NotBlank(message = "类型不能为空")
    @Schema(description = "image / video")
    private String category;

    private String ratio;
    private String resolution;
    private String quality;

    @Min(1)
    @Max(30)
    private Integer durationSec;

    @Min(1)
    @Max(4)
    private Integer copies = 1;

    @Valid
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
