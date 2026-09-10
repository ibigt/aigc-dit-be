package cn.aigc.dit.server.drama.text;

import lombok.Data;

@Data
public class TextChatResult {

    private String content;
    private String model;
    private String finishReason;

    public static TextChatResult of(String content, String model, String finishReason) {
        TextChatResult result = new TextChatResult();
        result.setContent(content == null ? "" : content);
        result.setModel(model);
        result.setFinishReason(finishReason);
        return result;
    }
}
