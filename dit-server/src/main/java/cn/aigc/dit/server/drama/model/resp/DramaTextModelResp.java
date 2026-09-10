package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "已启用文本模型（新建项目下拉；不含密钥）")
public class DramaTextModelResp {

    private Long id;
    private String name;
    private String provider;
}
