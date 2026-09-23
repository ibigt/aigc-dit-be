package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.credit.CreditCalculator;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次文本业务操作的用量收集器。合同校验失败的响应不记入，
 * 分片和并发的有效响应在业务成功后统一结算。
 */
public final class TextBillingSession {

    private static final TextBillingSession NOOP = new TextBillingSession();

    private final boolean billable;
    private final String billingRef;
    private final Long userId;
    private final long seriesId;
    private final long modelId;
    private final String operation;
    private final Map<String, Object> pricing;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong cachedPromptTokens = new AtomicLong();
    private final AtomicLong acceptedResponses = new AtomicLong();

    private TextBillingSession() {
        this.billable = false;
        this.billingRef = "";
        this.userId = null;
        this.seriesId = 0L;
        this.modelId = 0L;
        this.operation = "";
        this.pricing = Map.of();
    }

    public TextBillingSession(Long userId, long seriesId, long modelId,
                              String operation, Map<String, Object> pricing) {
        this.billable = true;
        this.billingRef = "TXT-" + UUID.randomUUID();
        this.userId = userId;
        this.seriesId = seriesId;
        this.modelId = modelId;
        this.operation = operation == null ? "TEXT_TASK" : operation;
        this.pricing = pricing == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(pricing));
    }

    public static TextBillingSession noop() {
        return NOOP;
    }

    public void record(TextChatResult result) {
        if (!billable || result == null) {
            return;
        }
        promptTokens.addAndGet(Math.max(0L, result.getPromptTokens()));
        completionTokens.addAndGet(Math.max(0L, result.getCompletionTokens()));
        cachedPromptTokens.addAndGet(Math.max(0L, result.getCachedPromptTokens()));
        acceptedResponses.incrementAndGet();
    }

    public boolean hasUsage() {
        return billable && acceptedResponses.get() > 0;
    }

    public long creditCost() {
        return CreditCalculator.calculateText(
                pricing, promptTokens.get(), completionTokens.get(), cachedPromptTokens.get());
    }

    public boolean isBillable() {
        return billable;
    }

    public String getBillingRef() {
        return billingRef;
    }

    public Long getUserId() {
        return userId;
    }

    public long getSeriesId() {
        return seriesId;
    }

    public long getModelId() {
        return modelId;
    }

    public String getOperation() {
        return operation;
    }

    public long getPromptTokens() {
        return promptTokens.get();
    }

    public long getCompletionTokens() {
        return completionTokens.get();
    }

    public long getCachedPromptTokens() {
        return Math.min(promptTokens.get(), cachedPromptTokens.get());
    }

    public long getAcceptedResponses() {
        return acceptedResponses.get();
    }
}
