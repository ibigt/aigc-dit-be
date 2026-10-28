package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "分镜字幕 cue 轻编辑保存")
public class DramaCaptionCueSaveReq {

    @NotEmpty
    @Valid
    private List<CueItem> cues = new ArrayList<>();

    @Data
    public static class CueItem {
        @NotNull
        @Schema(description = "开始时间（毫秒）")
        private Long startMs;
        @NotNull
        @Schema(description = "结束时间（毫秒）")
        private Long endMs;
        @NotNull
        @Schema(description = "字幕文本")
        private String text;
    }
}
