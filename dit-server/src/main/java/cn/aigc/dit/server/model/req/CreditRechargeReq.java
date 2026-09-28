package cn.aigc.dit.server.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Mock 充值")
public class CreditRechargeReq {

    @NotNull
    @Min(1)
    @Max(10000)
    @Schema(description = "充值金额（元），1 元 = 10 积分")
    private Integer amountYuan;

    @Schema(description = "wechat / alipay")
    private String payment;

    @NotBlank
    @Size(max = 64)
    @Schema(description = "客户端生成的充值幂等请求号")
    private String requestId;
}
