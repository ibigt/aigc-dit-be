package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "把账号素材库里的图片挂到分镜首帧或尾帧")
public class DramaShotFrameAssetReq {

    @NotNull(message = "请选择素材")
    @Schema(description = "账号素材库图片 id")
    private Long assetId;
}
