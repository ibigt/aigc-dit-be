package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分镜页全局生成方式（参考图 / 首尾帧）与参考图衔接落幅")
public class DramaVideoGenerateModeBindReq {

    @Schema(description = "r2v 参考图（默认）/ i2v 首尾帧；不传则保持原值")
    private String videoGenerateMode;

    @Schema(description = "参考图模式下是否把上一镜落幅当作额外 reference_image；不传则保持原值")
    private Boolean videoChainPrevTail;
}
