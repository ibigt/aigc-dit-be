package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.service.TextBillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextChatGatewayImplTest {

    @Mock
    private DramaSeriesMapper seriesMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private AesGcmEncryptor encryptor;
    @Mock
    private OpenAiCompatChatClient chatClient;
    @Mock
    private TextBillingService textBillingService;

    private TextChatGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        gateway = new TextChatGatewayImpl(
                seriesMapper, aiModelMapper, encryptor, chatClient, textBillingService);
    }

    @Test
    void resolveUsesSeriesBindingNotAnotherEnabledModel() {
        when(seriesMapper.selectById(21L)).thenReturn(series(21L, 11L));
        when(aiModelMapper.selectById(11L)).thenReturn(textModel(11L, "绑定的模型", true));

        Optional<ResolvedTextModel> resolved = gateway.resolve(21L);
        assertTrue(resolved.isPresent());
        assertEquals(11L, resolved.get().modelId());
        assertEquals("绑定的模型", resolved.get().name());
        assertEquals("agnes-2.5-flash", resolved.get().chatModel());
        verify(aiModelMapper, never()).selectList(any());
        verify(aiModelMapper, never()).selectById(eq(99L));
    }

    @Test
    void resolveEmptyWhenUnbound() {
        when(seriesMapper.selectById(21L)).thenReturn(series(21L, null));
        assertTrue(gateway.resolve(21L).isEmpty());
        assertFalse(gateway.llmAvailable(21L));
        verify(aiModelMapper, never()).selectById(any());
    }

    @Test
    void disabledBindingDoesNotFallBack() {
        when(seriesMapper.selectById(21L)).thenReturn(series(21L, 11L));
        when(aiModelMapper.selectById(11L)).thenReturn(textModel(11L, "已停用", false));
        BizException error = assertThrows(BizException.class, () -> gateway.resolve(21L));
        assertEquals(TextChatGateway.BINDING_UNUSABLE, error.getMessage());
        assertFalse(gateway.llmAvailable(21L));
        verify(aiModelMapper, never()).selectById(eq(99L));
    }

    @Test
    void chatCallsBoundModelOnly() {
        when(seriesMapper.selectById(21L)).thenReturn(series(21L, 11L));
        when(aiModelMapper.selectById(11L)).thenReturn(textModel(11L, "绑定的模型", true));
        when(encryptor.decrypt("v1:cipher")).thenReturn("sk-bound");
        when(chatClient.chat(eq("https://api.agnes-ai.cn/v1"), eq("sk-bound"), eq("agnes-2.5-flash"), any(), anyMap()))
                .thenReturn(TextChatResult.of("ok", "agnes-2.5-flash", "stop"));

        TextChatRequest req = new TextChatRequest(null, "ping", false);
        TextChatResult result = gateway.chat(21L, req);
        assertEquals("ok", result.getContent());
        verify(chatClient).chat("https://api.agnes-ai.cn/v1", "sk-bound", "agnes-2.5-flash", req,
                java.util.Map.of("enableThinking", false));
    }

    @Test
    void resolveInfersCustomAdapterWhenKeyBlank() {
        AiModel model = textModel(11L, "deepseek v4 flash", true);
        model.setAdapterKey("");
        model.setProvider(TextAdapterCatalog.PROVIDER_CUSTOM);
        model.setEndpoint("https://api.deepseek.com/v1");
        model.setModelKey("deepseek-v4-flash");
        when(seriesMapper.selectById(21L)).thenReturn(series(21L, 11L));
        when(aiModelMapper.selectById(11L)).thenReturn(model);

        Optional<ResolvedTextModel> resolved = gateway.resolve(21L);
        assertTrue(resolved.isPresent());
        assertTrue(gateway.llmAvailable(21L));
        assertEquals(TextAdapterCatalog.OPENAI_COMPAT,
                TextAdapterCatalog.resolveKey(model.getAdapterKey(), model.getProvider(), model.getModelKey()));
    }

    @Test
    void resolveAndChatAllowMissingApiKey() {
        AiModel model = textModel(11L, "本地网关", true);
        model.setApiKeyEnc(null);
        model.setAdapterKey(TextAdapterCatalog.OPENAI_COMPAT);
        model.setProvider(TextAdapterCatalog.PROVIDER_CUSTOM);
        model.setEndpoint("http://172.171.114.10:8080/v1");
        model.setModelKey("deepseek-v3-flash");
        when(seriesMapper.selectById(21L)).thenReturn(series(21L, 11L));
        when(aiModelMapper.selectById(11L)).thenReturn(model);
        when(encryptor.decrypt(isNull())).thenReturn(null);
        when(chatClient.chat(eq("http://172.171.114.10:8080/v1"), isNull(), eq("deepseek-v3-flash"), any(), anyMap()))
                .thenReturn(TextChatResult.of("ok", "deepseek-v3-flash", "stop"));

        Optional<ResolvedTextModel> resolved = gateway.resolve(21L);
        assertTrue(resolved.isPresent());
        assertTrue(gateway.llmAvailable(21L));

        TextChatRequest req = new TextChatRequest(null, "ping", false);
        TextChatResult result = gateway.chat(21L, req);
        assertEquals("ok", result.getContent());
        verify(chatClient).chat("http://172.171.114.10:8080/v1", null, "deepseek-v3-flash", req,
                java.util.Map.of("enableThinking", false));
    }

    @Test
    void opensTaskBillingFromSeriesOwnerAndBoundModelPricing() {
        DramaSeries series = series(21L, 11L);
        AiModel model = textModel(11L, "绑定的模型", true);
        model.setPricing(java.util.Map.of(
                "unit", "千Token", "sale", java.util.Map.of("input", 1, "output", 2, "cache", 0.5)));
        when(seriesMapper.selectById(21L)).thenReturn(series);
        when(aiModelMapper.selectById(11L)).thenReturn(model);

        TextBillingSession session = gateway.openBilling(21L, "SCRIPT_PARSE");

        assertTrue(session.isBillable());
        assertEquals(7L, session.getUserId());
        assertEquals(21L, session.getSeriesId());
        assertEquals(11L, session.getModelId());
        assertEquals("SCRIPT_PARSE", session.getOperation());
    }

    private static DramaSeries series(long id, Long textModelId) {
        DramaSeries series = new DramaSeries();
        series.setId(id);
        series.setUserId(7L);
        series.setTextModelId(textModelId);
        return series;
    }

    private static AiModel textModel(long id, String name, boolean enabled) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setName(name);
        model.setCategory(ModelCategory.TEXT);
        model.setEnabled(enabled);
        model.setAdapterKey(TextAdapterCatalog.AGNES_TEXT);
        model.setProvider(TextAdapterCatalog.PROVIDER_OFFICIAL);
        model.setEndpoint("https://api.agnes-ai.cn/v1");
        model.setModelKey("agnes-2.5-flash");
        model.setApiKeyEnc("v1:cipher");
        return model;
    }
}
