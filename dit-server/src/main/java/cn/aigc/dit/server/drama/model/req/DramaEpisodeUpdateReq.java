package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "更新剧集基础信息")
public class DramaEpisodeUpdateReq {

    @NotBlank(message = "集名称不能为空")
    @Size(max = 128)
    private String name;
}
