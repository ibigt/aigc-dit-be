package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "已启用图像模型（资产页全局下拉；不含密钥）")
public class DramaImageModelResp {

    private Long id;
    private String name;
    private String provider;
    private String adapterKey;
}
