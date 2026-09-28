package cn.aigc.dit.server.script;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.drama.text.OpenAiCompatChatClient;
import cn.aigc.dit.server.drama.text.TextChatRequest;
import cn.aigc.dit.server.drama.text.TextChatResult;
import cn.aigc.dit.server.drama.text.TextChatGatewayImpl;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 剧本域的轻量 LLM Gateway：只负责模型解析与 OpenAI-compatible 调用。 */
@Service
@RequiredArgsConstructor
public class ScriptLlmGateway {

    private final AiModelMapper modelMapper;
    private final AesGcmEncryptor encryptor;
    private final OpenAiCompatChatClient client;

    public TextChatResult chat(Long modelId, TextChatRequest request) {
        if (modelId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先选择文本创作模型");
        }
        AiModel model = modelMapper.selectById(modelId);
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())
                || !ModelCategory.TEXT.equalsIgnoreCase(model.getCategory())
                || !StringUtils.hasText(model.getEndpoint())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所选文本模型不可用，请在模型管理中检查配置");
        }
        String modelKey = TextChatGatewayImpl.chatModelOf(model);
        String apiKey = encryptor.decrypt(model.getApiKeyEnc());
        return client.chat(model.getEndpoint(), apiKey, modelKey, request,
                model.getAdapterConfig());
    }

    public String modelName(Long modelId) {
        if (modelId == null) {
            return null;
        }
        AiModel model = modelMapper.selectById(modelId);
        return model == null ? null : model.getName();
    }
}
