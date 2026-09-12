package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新增分镜；不传 chapterId 时挂到最后一场")
public class DramaShotCreateReq {

    private Long chapterId;

    @Size(max = 128)
    private String title;
}
