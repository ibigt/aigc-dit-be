package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "资产文生图；模型用系列绑定，不按素材选")
public class DramaAssetGenerateReq {

    @Schema(description = "出图分辨率：1K / 2K，默认 1K")
    private String resolution;
}
