package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新增分镜；不传 chapterId 时挂到最后一场，afterShotId 则插到该镜之后并重编号")
public class DramaShotCreateReq {

    private Long chapterId;

    @Schema(description = "插到该镜之后；不传则追加到本场末尾")
    private Long afterShotId;

    @Size(max = 128)
    private String title;
}
