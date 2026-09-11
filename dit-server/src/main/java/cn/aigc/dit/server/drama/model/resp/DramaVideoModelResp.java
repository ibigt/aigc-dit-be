package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "已启用视频模型（分镜页全局下拉；不含密钥）")
public class DramaVideoModelResp {

    private Long id;
    private String name;
    private String provider;
    private String adapterKey;
}
