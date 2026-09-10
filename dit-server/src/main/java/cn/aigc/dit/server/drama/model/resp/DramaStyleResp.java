package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "漫剧风格图例（不含编译前缀）")
public class DramaStyleResp {

    private String key;
    private String name;
    private String cover;
}
