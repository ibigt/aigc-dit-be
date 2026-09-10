package cn.aigc.dit.server.drama.text;

import lombok.Data;

/**
 * 文本通道最小请求。不含侦探社 prompt / 分场规则；那些由乙的 DramaTextService 组装。
 */
@Data
public class TextChatRequest {

    private String system;
    private String user;
    private Boolean jsonMode;

    public TextChatRequest() {
    }

    public TextChatRequest(String system, String user, Boolean jsonMode) {
        this.system = system;
        this.user = user;
        this.jsonMode = jsonMode;
    }

    public boolean jsonMode() {
        return Boolean.TRUE.equals(jsonMode);
    }
}
