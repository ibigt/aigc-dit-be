package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TextChatGatewayImpl implements TextChatGateway {

    private final DramaSeriesMapper seriesMapper;
    private final AiModelMapper aiModelMapper;
    private final AesGcmEncryptor encryptor;
    private final OpenAiCompatChatClient chatClient;

    @Override
    public boolean llmAvailable(long seriesId) {
        try {
            return resolve(seriesId).isPresent();
        } catch (BizException e) {
            return false;
        }
    }

    @Override
    public Optional<ResolvedTextModel> resolve(long seriesId) {
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        Long modelId = series.getTextModelId();
        if (modelId == null) {
            return Optional.empty();
        }
        AiModel model = aiModelMapper.selectById(modelId);
        if (!usable(model)) {
            throw new BizException(ErrorCode.BAD_REQUEST, BINDING_UNUSABLE);
        }
        return Optional.of(toResolved(model));
    }

    @Override
    public TextChatResult chat(long seriesId, TextChatRequest req) {
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        if (series.getTextModelId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目未绑定文本模型");
        }
        AiModel model = aiModelMapper.selectById(series.getTextModelId());
        if (!usable(model)) {
            throw new BizException(ErrorCode.BAD_REQUEST, BINDING_UNUSABLE);
        }
        String apiKey = encryptor.decrypt(model.getApiKeyEnc());
        ResolvedTextModel resolved = toResolved(model);
        return chatClient.chat(resolved.endpoint(), apiKey, resolved.chatModel(), req);
    }

    static boolean usable(AiModel model) {
        return model != null
                && Boolean.TRUE.equals(model.getEnabled())
                && ModelCategory.TEXT.equalsIgnoreCase(model.getCategory())
                && StringUtils.hasText(model.getEndpoint())
                && TextAdapterCatalog.find(model.getAdapterKey()) != null;
    }

    static ResolvedTextModel toResolved(AiModel model) {
        return new ResolvedTextModel(
                model.getId(),
                model.getName(),
                model.getAdapterKey(),
                model.getProvider(),
                model.getEndpoint(),
                chatModelOf(model));
    }

    public static String chatModelOf(AiModel model) {
        Map<String, Object> config = model.getAdapterConfig();
        if (config != null) {
            Object chatModel = config.get("chatModel");
            if (chatModel != null && StringUtils.hasText(String.valueOf(chatModel))) {
                return String.valueOf(chatModel).trim();
            }
        }
        return model.getModelKey();
    }
}
