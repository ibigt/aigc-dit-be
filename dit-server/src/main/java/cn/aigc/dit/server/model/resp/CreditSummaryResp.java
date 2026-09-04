package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "积分汇总")
public class CreditSummaryResp {
    private Long balance;
    private Long totalRecharge;
    private Long monthSpent;
}
