package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "分镜批量出片请求")
public class DramaShotBatchGenerateReq {

    @NotEmpty
    @Schema(description = "选中的分镜 id；须同一场次")
    private List<Long> shotIds;

    @NotNull
    @Schema(description = "出片方式：r2v / i2v")
    private String videoGenerateMode;

    @Schema(description = "r2v 时是否衔接上一镜落幅；默认 false")
    private Boolean chainPrevTail;

    @Schema(description = "i2v 首帧：auto | prev-tail / prev_tail")
    private String i2vFirstFrameMode;

    @Schema(description = "i2v+引用尾帧时，场内第一镜是否自动静帧；默认 true")
    private Boolean firstShotAutoStill;
}
