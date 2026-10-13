package cn.aigc.dit.server.drama.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色审核按钮是否显示。不回传密钥")
public class DramaArkReviewAvailabilityResp {

    private boolean configured;
}
