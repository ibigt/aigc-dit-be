package cn.aigc.dit.server.model.resp;

import lombok.Data;

@Data
public class AdminRechargeSummaryResp {
    private long orderCount;
    private long paidAmountYuan;
    private long creditedAmount;
    private long exceptionCount;
}
