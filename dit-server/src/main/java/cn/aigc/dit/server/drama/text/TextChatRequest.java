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
    /** 结构化阶段可按输出规模单独限制，避免资产/分镜阶段生成无关长文本。 */
    private Integer maxTokens;

    public TextChatRequest() {
    }

    public TextChatRequest(String system, String user, Boolean jsonMode) {
        this.system = system;
        this.user = user;
        this.jsonMode = jsonMode;
    }

    public TextChatRequest(String system, String user, Boolean jsonMode, Integer maxTokens) {
        this(system, user, jsonMode);
        this.maxTokens = maxTokens;
    }

    public boolean jsonMode() {
        return Boolean.TRUE.equals(jsonMode);
    }
}
