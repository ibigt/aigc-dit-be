package cn.aigc.dit.server.model.resp;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AdminRechargeOrderResp {
    private Long id;
    private String orderNo;
    private Long userId;
    private String username;
    private String email;
    private Integer amountYuan;
    private Long creditAmount;
    private String paymentChannel;
    private String status;
    private String providerTradeNo;
    private Long ledgerAmount;
    private String reconciliationStatus;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
}
