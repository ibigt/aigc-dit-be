package cn.aigc.dit.server.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Schema(description = "充值订单")
public class RechargeOrderResp {

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "充值金额（元）")
    private Integer amountYuan;

    @Schema(description = "到账积分")
    private Long creditAmount;

    @Schema(description = "支付渠道：alipay / wechat")
    private String payment;

    @Schema(description = "订单状态：PAYING / PAID")
    private String status;

    @Schema(description = "支付二维码内容；Mock 渠道为模拟值")
    private String qrCode;

    @Schema(description = "支付完成时间")
    private OffsetDateTime paidAt;
}
