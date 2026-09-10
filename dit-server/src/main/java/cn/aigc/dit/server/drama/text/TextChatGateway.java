package cn.aigc.dit.server.drama.text;

import java.util.Optional;

/**
 * 漫剧文本插座。按系列 {@code text_model_id} 解析，禁止全局默认、禁止环境变量覆盖。
 * 乙只依赖本接口，不要自写 HTTP 客户端。
 */
public interface TextChatGateway {

    String BINDING_UNUSABLE = "项目绑定的文本模型已停用，请在基础信息中更换";

    boolean llmAvailable(long seriesId);

    /**
     * 读系列绑定。无绑定 → empty；绑定已停用/删除 → 抛 {@link #BINDING_UNUSABLE}。
     */
    Optional<ResolvedTextModel> resolve(long seriesId);

    TextChatResult chat(long seriesId, TextChatRequest req);
}
