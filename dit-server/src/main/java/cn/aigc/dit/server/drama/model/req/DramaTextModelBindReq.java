package cn.aigc.dit.server.drama.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "剧本页绑定文本模型；传 null 表示解绑并回退规则分场")
public class DramaTextModelBindReq {

    @Schema(description = "已启用 category=text 的模型 id；为空表示不绑定")
    private Long textModelId;
}
