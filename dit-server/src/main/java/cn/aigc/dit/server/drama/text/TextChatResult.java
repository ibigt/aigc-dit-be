package cn.aigc.dit.server.drama.text;

import lombok.Data;

@Data
public class TextChatResult {

    private String content;
    private String model;
    private String finishReason;
    private long promptTokens;
    private long completionTokens;
    private long cachedPromptTokens;
    /**
     * 实际下发给模型的输出预算（max_tokens）。未设置时为 null，v1 调用方不读它。
     */
    private Integer outputBudget;

    public static TextChatResult of(String content, String model, String finishReason) {
        return of(content, model, finishReason, 0L, 0L, 0L);
    }

    public static TextChatResult of(String content, String model, String finishReason,
                                    long promptTokens, long completionTokens, long cachedPromptTokens) {
        TextChatResult result = new TextChatResult();
        result.setContent(content == null ? "" : content);
        result.setModel(model);
        result.setFinishReason(finishReason);
        result.setPromptTokens(Math.max(0L, promptTokens));
        result.setCompletionTokens(Math.max(0L, completionTokens));
        result.setCachedPromptTokens(Math.min(result.getPromptTokens(), Math.max(0L, cachedPromptTokens)));
        return result;
    }

    /** 附带实际下发的输出预算。 */
    public TextChatResult withOutputBudget(Integer outputBudget) {
        this.outputBudget = outputBudget;
        return this;
    }
}
