package cn.aigc.dit.server.model.resp;

import lombok.Data;

@Data
public class AdminTextUsageSummaryResp {
    private long taskCount;
    private long promptTokens;
    private long completionTokens;
    private long cachedPromptTokens;
    private long creditCost;
    private long ledgerCost;
    private long exceptionCount;
}
