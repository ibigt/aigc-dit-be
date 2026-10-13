package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色审核密钥。留空的字段保持已加密的原值")
public class ArkReviewCredentialSaveReq {

    private String accessKey;
    private String secretKey;
}
