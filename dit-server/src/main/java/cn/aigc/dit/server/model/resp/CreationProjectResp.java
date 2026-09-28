package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "作品所属项目")
public class CreationProjectResp {
    @Schema(description = "筛选项，quick:{会话ID}、drama:{短剧项目ID} 或 none")
    private String key;
    @Schema(description = "quick 快速创作，drama 短剧，none 未归入项目")
    private String kind;
    private Long id;
    private String name;
}
