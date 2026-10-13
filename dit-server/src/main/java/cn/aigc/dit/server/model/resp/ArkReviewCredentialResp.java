package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色审核密钥是否已配置。不回传明文")
public class ArkReviewCredentialResp {

    private boolean configured;
    private boolean hasAccessKey;
    private boolean hasSecretKey;
}
