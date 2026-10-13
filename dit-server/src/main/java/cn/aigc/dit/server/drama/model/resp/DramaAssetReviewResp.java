package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色图方舟素材审核结果")
public class DramaAssetReviewResp {

    private String status;
    private String assetId;
    private String storageKey;
}
