package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分镜参考资产（Picture 序号与列表顺序一致）")
public class DramaShotReferenceResp {

    private Integer pictureIndex;
    private Long assetId;
    private String name;
    private String type;
    private String stateTag;
    private Integer identityVersion;
    private String imageUrl;
    private Boolean locked;
}
