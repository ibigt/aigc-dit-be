package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.service.TextBillingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

@Service
public class TextChatGatewayImpl implements TextChatGateway {

    private final DramaSeriesMapper seriesMapper;
    private final AiModelMapper aiModelMapper;
    private final AesGcmEncryptor encryptor;
    private final OpenAiCompatChatClient chatClient;
    private final TextBillingService textBillingService;

    @Autowired
    public TextChatGatewayImpl(DramaSeriesMapper seriesMapper, AiModelMapper aiModelMapper,
                               AesGcmEncryptor encryptor, OpenAiCompatChatClient chatClient,
                               TextBillingService textBillingService) {
        this.seriesMapper = seriesMapper;
        this.aiModelMapper = aiModelMapper;
        this.encryptor = encryptor;
        this.chatClient = chatClient;
        this.textBillingService = textBillingService;
    }

    TextChatGatewayImpl(DramaSeriesMapper seriesMapper, AiModelMapper aiModelMapper,
                        AesGcmEncryptor encryptor, OpenAiCompatChatClient chatClient) {
        this(seriesMapper, aiModelMapper, encryptor, chatClient, null);
    }

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
        return chatClient.chat(resolved.endpoint(), apiKey, resolved.chatModel(), req, resolved.adapterConfig());
    }

    @Override
    public TextBillingSession openBilling(long seriesId, String operation) {
        if (textBillingService == null) {
            return TextBillingSession.noop();
        }
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        AiModel model = series.getTextModelId() == null ? null : aiModelMapper.selectById(series.getTextModelId());
        if (!usable(model)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    series.getTextModelId() == null ? "项目未绑定文本模型" : BINDING_UNUSABLE);
        }
        return new TextBillingSession(series.getUserId(), seriesId, model.getId(), operation, model.getPricing());
    }

    @Override
    public void settleBilling(TextBillingSession session) {
        if (textBillingService != null) {
            textBillingService.settle(session);
        }
    }

    public static boolean usable(AiModel model) {
        if (model == null
                || !Boolean.TRUE.equals(model.getEnabled())
                || !ModelCategory.TEXT.equalsIgnoreCase(model.getCategory())
                || !StringUtils.hasText(model.getEndpoint())) {
            return false;
        }
        // 允许 adapter_key 为空：按来源/模型名推断 agnes-text / openai-compat。
        // 下拉只按 category+enabled 列出，过严认 key 会把已绑定的自定义模型判成「未绑定」。
        return TextAdapterCatalog.resolveKey(model.getAdapterKey(), model.getProvider(), model.getModelKey()) != null;
    }

    static ResolvedTextModel toResolved(AiModel model) {
        return new ResolvedTextModel(
                model.getId(),
                model.getName(),
                model.getAdapterKey(),
                model.getProvider(),
                model.getEndpoint(),
                chatModelOf(model),
                model.getAdapterConfig());
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
