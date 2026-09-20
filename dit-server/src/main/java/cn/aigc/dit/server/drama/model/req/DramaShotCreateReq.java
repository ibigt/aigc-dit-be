package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新增分镜；不传 chapterId 时挂到最后一场；afterShotId / beforeShotId 二选一插位并重编号")
public class DramaShotCreateReq {

    private Long chapterId;

    @Schema(description = "插到该镜之后；与 beforeShotId 二选一，都不传则追加到本场末尾")
    private Long afterShotId;

    @Schema(description = "插到该镜之前（胶片条镜1前的 +）；与 afterShotId 二选一")
    private Long beforeShotId;

    @Size(max = 128)
    private String title;
}
