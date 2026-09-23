package cn.aigc.dit.server.model.resp;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AdminTextUsageResp {
    private Long id;
    private String billingRef;
    private Long userId;
    private String username;
    private String email;
    private Long seriesId;
    private Long modelId;
    private String modelName;
    private String operation;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedPromptTokens;
    private Long totalTokens;
    private Long creditCost;
    private Long ledgerCost;
    private String reconciliationStatus;
    private OffsetDateTime createdAt;
}
