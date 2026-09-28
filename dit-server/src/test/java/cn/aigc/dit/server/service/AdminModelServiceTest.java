package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.SpeechVoice;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.SpeechVoiceMapper;
import cn.aigc.dit.server.model.req.AdminModelSaveReq;
import cn.aigc.dit.server.model.req.AdminModelTestConnectionReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminModelServiceTest {

    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private AesGcmEncryptor encryptor;
    @Mock
    private cn.aigc.dit.server.drama.text.OpenAiCompatChatClient chatClient;

    @Mock
    private cn.aigc.dit.generate.GenerateProviderRegistry providerRegistry;

    @Mock
    private cn.aigc.dit.server.drama.mapper.DramaSeriesMapper seriesMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private CreationMapper creationMapper;
    @Mock
    private SpeechVoiceMapper speechVoiceMapper;

    private AdminModelService service;

    @BeforeEach
    void setUp() {
        service = new AdminModelService(encryptor, chatClient, providerRegistry, seriesMapper, genTaskMapper, creationMapper, speechVoiceMapper);
        ReflectionTestUtils.setField(service, "baseMapper", modelMapper);
    }

    @Test
    void createEncryptsApiKeyAndHidesItInResponse() {
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(encryptor.encrypt("sk-test")).thenReturn("v1:cipher");
        when(modelMapper.insert(any(AiModel.class))).thenAnswer(invocation -> {
            AiModel model = invocation.getArgument(0);
            model.setId(3L);
            assertEquals("v1:cipher", model.getApiKeyEnc());
            return 1;
        });

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Seedream 5.0");
        req.setModelKey("seedream-5");
        req.setAdapterKey("gpt-image-2");
        req.setProvider("RunningHub");
        req.setCategory(ModelCategory.IMAGE);
        req.setApiKey("sk-test");
        req.setPricing(Map.of("unit", "张"));
        req.setCapabilities(List.of("image", "t2i"));
        req.setAdapterConfig(Map.of("t2iPath", "rhart-image-g-2/text-to-image"));

        var resp = service.create(req);
        assertEquals(3L, resp.getId());
        assertTrue(resp.isHasApiKey());
        assertEquals("图像", resp.getBadge());
        assertEquals(List.of("image", "t2i"), resp.getCapabilities());
        assertEquals("rhart-image-g-2/text-to-image", resp.getAdapterConfig().get("t2iPath"));
        assertEquals("gpt-image-2", resp.getAdapterKey());
        assertEquals("RunningHub", resp.getProvider());

    }

    @Test
    void createKeepsExplicitZeroPricing() {
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(modelMapper.insert(any(AiModel.class))).thenAnswer(invocation -> {
            AiModel model = invocation.getArgument(0);
            model.setId(4L);
            assertEquals("0", ((Map<?, ?>) model.getPricing().get("sale")).get("output"));
            return 1;
        });

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Free Image");
        req.setModelKey("free-image");
        req.setAdapterKey("gpt-image-2");
        req.setProvider("RunningHub");
        req.setCategory(ModelCategory.IMAGE);
        req.setPricing(Map.of(
                "unit", "张",
                "sale", Map.of("output", "0"),
                "cost", Map.of("output", "¥0")));

        var resp = service.create(req);

        assertEquals("0", ((Map<?, ?>) resp.getPricing().get("sale")).get("output"));
    }

    @Test
    void createRejectsNegativePricingBeforeInsert() {
        when(modelMapper.selectCount(any())).thenReturn(0L);

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Invalid Image");
        req.setModelKey("invalid-image");
        req.setAdapterKey("gpt-image-2");
        req.setProvider("RunningHub");
        req.setCategory(ModelCategory.IMAGE);
        req.setPricing(Map.of("unit", "张", "sale", Map.of("output", "-1 积分")));

        assertThrows(BizException.class, () -> service.create(req));
        org.mockito.Mockito.verify(modelMapper, org.mockito.Mockito.never()).insert(any(AiModel.class));
    }

    @Test
    void deleteUnbindsSeriesAndHistoryThenRemovesModel() {
        AiModel model = new AiModel();
        model.setId(11L);
        when(modelMapper.selectById(11L)).thenReturn(model);
        when(seriesMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(genTaskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(0);
        when(creationMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(0);
        when(modelMapper.deleteById(11L)).thenReturn(1);

        service.delete(11L);

        org.mockito.Mockito.verify(seriesMapper, org.mockito.Mockito.times(3))
                .update(org.mockito.ArgumentMatchers.isNull(), any());
        org.mockito.Mockito.verify(modelMapper).deleteById(11L);
    }

    @Test
    void createAgnesImageDerivesOfficialProviderAndEndpoint() {
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(encryptor.encrypt("agnes-key")).thenReturn("v1:cipher");
        when(modelMapper.insert(any(AiModel.class))).thenAnswer(invocation -> {
            AiModel model = invocation.getArgument(0);
            model.setId(9L);
            assertEquals("官方API", model.getProvider());
            assertEquals("https://api.agnes-ai.cn/v1", model.getEndpoint());
            assertEquals(AdapterCatalog.AGNES_IMAGE_2_5, model.getAdapterKey());
            return 1;
        });

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Agnes Image 2.5 Flash");
        req.setModelKey("agnes-image-2.5-flash");
        req.setAdapterKey("agnes-image-2.5");
        req.setCategory(ModelCategory.IMAGE);
        req.setApiKey("agnes-key");
        req.setCapabilities(List.of("image", "t2i", "i2i"));

        var resp = service.create(req);
        assertEquals(9L, resp.getId());
        assertEquals("官方API", resp.getProvider());
        assertEquals(List.of("image", "t2i", "i2i"), resp.getCapabilities());
    }

    @Test
    void createSeedanceTokenPlanKeepsSourceAndPlanEndpoint() {
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(encryptor.encrypt("ark-plan")).thenReturn("v1:cipher");
        when(modelMapper.insert(any(AiModel.class))).thenAnswer(invocation -> {
            AiModel model = invocation.getArgument(0);
            model.setId(21L);
            assertEquals(AdapterCatalog.PROVIDER_OFFICIAL_TOKEN_PLAN, model.getProvider());
            assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3", model.getEndpoint());
            assertEquals(AdapterCatalog.SEEDANCE_2_0, model.getAdapterKey());
            return 1;
        });

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Seedance 2.0 Fast Plan");
        req.setModelKey("doubao-seedance-2.0-fast");
        req.setAdapterKey("seedance-2.0");
        req.setProvider("官方API（token plan）");
        req.setCategory(ModelCategory.VIDEO);
        req.setApiKey("ark-plan");
        req.setCapabilities(List.of("video", "t2v", "r2v", "i2v"));

        var resp = service.create(req);
        assertEquals(AdapterCatalog.PROVIDER_OFFICIAL_TOKEN_PLAN, resp.getProvider());
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3", resp.getEndpoint());
        assertTrue(resp.getSupportsAudioReference());
        assertEquals(3, resp.getMaxAudioReferences());
        assertFalse(resp.getSupportsAudioOnlyReference());
    }

    @Test
    void publicSeedance25ExposesAudioUploadCapability() {
        AiModel model = new AiModel();
        model.setAdapterKey(AdapterCatalog.SEEDANCE_2_5);
        model.setProvider(AdapterCatalog.PROVIDER_OFFICIAL);
        model.setCategory(ModelCategory.VIDEO);
        model.setModelKey("doubao-seedance-2-5-260628");

        var resp = service.toPublicResp(model);

        assertTrue(resp.getSupportsAudioReference());
        assertEquals(10, resp.getMaxAudioReferences());
        assertTrue(resp.getSupportsAudioOnlyReference());
    }

    @Test
    void publicMiniMaxSpeechUsesSystemVoiceCatalog() {
        SpeechVoice voice = new SpeechVoice();
        voice.setVoiceId("Japanese_CalmLady");
        voice.setName("Calm Lady");
        voice.setLanguage("日文");
        when(speechVoiceMapper.selectList(any())).thenReturn(List.of(voice));

        AiModel model = new AiModel();
        model.setAdapterKey(AdapterCatalog.MINIMAX_SPEECH);
        model.setCategory(ModelCategory.AUDIO);
        model.setAdapterConfig(Map.of("voices", List.of("custom-voice")));

        var voices = (List<?>) service.toPublicResp(model).getAudioOptions().get("voices");
        assertEquals(2, voices.size());
        assertEquals(Map.of("id", "Japanese_CalmLady", "name", "Calm Lady", "language", "日文"), voices.get(0));
        assertEquals(Map.of("id", "custom-voice", "name", "custom-voice", "language", "自定义"), voices.get(1));
    }

    @Test
    void createMiniMaxComfyUiKeepsLocalSourceAndEndpoint() {
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(modelMapper.insert(any(AiModel.class))).thenAnswer(invocation -> {
            AiModel model = invocation.getArgument(0);
            model.setId(22L);
            assertEquals(AdapterCatalog.PROVIDER_COMFYUI, model.getProvider());
            assertEquals(AdapterCatalog.MINIMAX_COMFY_ENDPOINT, model.getEndpoint());
            assertEquals(AdapterCatalog.MINIMAX_H3, model.getAdapterKey());
            return 1;
        });

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("MiniMax-H3 Comfy");
        req.setModelKey("minimax-h3");
        req.setAdapterKey("minimax-h3");
        req.setProvider("ComfyUI");
        req.setCategory(ModelCategory.VIDEO);
        req.setCapabilities(List.of("video", "t2v", "r2v", "i2v"));

        var resp = service.create(req);
        assertEquals(AdapterCatalog.PROVIDER_COMFYUI, resp.getProvider());
        assertEquals(AdapterCatalog.MINIMAX_COMFY_ENDPOINT, resp.getEndpoint());
    }

    @Test
    void updateKeepsExistingKeyWhenBlank() {
        AiModel existing = new AiModel();
        existing.setId(3L);
        existing.setApiKeyEnc("v1:old");
        existing.setModelKey("seedream-5");
        existing.setCategory(ModelCategory.IMAGE);
        when(modelMapper.selectById(3L)).thenReturn(existing);
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(modelMapper.updateById(any(AiModel.class))).thenReturn(1);

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Seedream 5.0");
        req.setModelKey("seedream-5");
        req.setAdapterKey("gpt-image-2");
        req.setProvider("RunningHub");
        req.setCategory(ModelCategory.IMAGE);
        req.setApiKey("  ");
        req.setEnabled(false);

        var resp = service.update(3L, req);
        assertTrue(resp.isHasApiKey());
        assertFalse(resp.getEnabled());
        assertEquals("v1:old", existing.getApiKeyEnc());
    }

    @Test
    void publicListDropsSecrets() {
        AiModel model = new AiModel();
        model.setId(1L);
        model.setName("Mock");
        model.setModelKey("mock-1");
        model.setProvider("mock");
        model.setCategory(ModelCategory.VIDEO);
        model.setEnabled(true);
        model.setApiKeyEnc("v1:secret");
        model.setEndpoint("http://127.0.0.1:8188");
        model.setCapabilities(List.of("video", "t2v", "r2v", "v2v"));

        var pub = service.toPublicResp(model);
        assertEquals("Mock", pub.getName());
        assertEquals(Boolean.TRUE, pub.getEnabled());
        assertEquals(List.of("video", "t2v", "r2v", "i2v"), pub.getCapabilities());
    }

    @Test
    void normalizeCapabilitiesRewritesLegacyVideoModes() {
        assertEquals(List.of("video", "t2v", "r2v", "i2v"),
                AdminModelService.normalizeCapabilities(List.of("video", "t2v", "r2v", "v2v", "frames"), "video"));
    }

    @Test
    void createTextModelUsesTextCatalogNotVideoAgnes() {
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(encryptor.encrypt("sk-text")).thenReturn("v1:cipher");
        when(modelMapper.insert(any(AiModel.class))).thenAnswer(invocation -> {
            AiModel model = invocation.getArgument(0);
            model.setId(12L);
            assertEquals("agnes-text", model.getAdapterKey());
            assertEquals("官方API", model.getProvider());
            assertEquals("https://api.agnes-ai.cn/v1", model.getEndpoint());
            assertEquals(ModelCategory.TEXT, model.getCategory());
            assertEquals(List.of("text"), model.getCapabilities());
            assertEquals("agnes-2.5-flash", model.getAdapterConfig().get("chatModel"));
            assertEquals(false, model.getAdapterConfig().get("enableThinking"));
            return 1;
        });

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Agnes 对话");
        req.setModelKey("agnes-2.5-flash");
        req.setAdapterKey("agnes-text");
        req.setCategory(ModelCategory.TEXT);
        req.setApiKey("sk-text");

        var resp = service.create(req);
        assertEquals(12L, resp.getId());
        assertEquals("官方API", resp.getProvider());
        assertEquals("agnes-text", resp.getAdapterKey());
        assertEquals("文本", resp.getBadge());
        assertEquals(List.of("text"), resp.getCapabilities());
    }

    @Test
    void createTextModelPreservesExplicitThinkingSetting() {
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(modelMapper.insert(any(AiModel.class))).thenAnswer(invocation -> {
            AiModel model = invocation.getArgument(0);
            assertEquals(true, model.getAdapterConfig().get("enableThinking"));
            return 1;
        });

        AdminModelSaveReq req = new AdminModelSaveReq();
        req.setName("Qwen 对话");
        req.setModelKey("Qwen/Qwen3.6-35B-A3B");
        req.setAdapterKey("openai-compat");
        req.setProvider("自定义配置");
        req.setEndpoint("https://example.com/v1");
        req.setCategory(ModelCategory.TEXT);
        req.setAdapterConfig(Map.of("enableThinking", true));

        service.create(req);
    }

    @Test
    void testConnectionUsesStoredKeyWhenBlank() {
        AiModel existing = new AiModel();
        existing.setId(12L);
        existing.setCategory(ModelCategory.TEXT);
        existing.setEndpoint("https://api.agnes-ai.cn/v1");
        existing.setModelKey("agnes-2.5-flash");
        existing.setApiKeyEnc("v1:cipher");
        when(modelMapper.selectById(12L)).thenReturn(existing);
        when(encryptor.decrypt("v1:cipher")).thenReturn("sk-stored");

        AdminModelTestConnectionReq req = new AdminModelTestConnectionReq();
        req.setId(12L);
        req.setEndpoint("https://api.agnes-ai.cn/v1");
        req.setModelKey("agnes-2.5-flash");
        req.setApiKey("");

        var resp = service.testConnection(req);
        assertEquals("连接成功", resp.getMessage());
        org.mockito.Mockito.verify(chatClient).ping(
                "https://api.agnes-ai.cn/v1", "sk-stored", "agnes-2.5-flash");
    }

    @Test
    void testConnectionAllowsBlankApiKey() {
        AdminModelTestConnectionReq req = new AdminModelTestConnectionReq();
        req.setCategory(ModelCategory.TEXT);
        req.setAdapterKey("openai-compat");
        req.setEndpoint("http://172.171.114.10:8080/v1");
        req.setModelKey("deepseek-v3-flash");
        req.setApiKey("");

        var resp = service.testConnection(req);
        assertEquals("连接成功", resp.getMessage());
        org.mockito.Mockito.verify(chatClient).ping(
                "http://172.171.114.10:8080/v1", null, "deepseek-v3-flash");
    }

    @Test
    void testConnectionDispatchesImageProviderWhenCategoryAndAdapterGiven() {
        cn.aigc.dit.generate.GenerateProvider provider =
                org.mockito.Mockito.mock(cn.aigc.dit.generate.GenerateProvider.class);
        when(providerRegistry.resolveByAdapter(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(provider);

        AdminModelTestConnectionReq req = new AdminModelTestConnectionReq();
        req.setCategory(ModelCategory.IMAGE);
        req.setAdapterKey("gpt-image-2");
        req.setProvider("RunningHub");
        req.setEndpoint("https://www.runninghub.ai");
        req.setApiKey("sk-rh");
        req.setModelKey("gpt-image-2");

        var resp = service.testConnection(req);
        assertEquals("连接成功", resp.getMessage());
        verify(providerRegistry).resolveByAdapter("gpt-image-2", "RunningHub",
                ModelCategory.IMAGE, "gpt-image-2");
        verify(provider).ping(org.mockito.ArgumentMatchers.argThat(
                (cn.aigc.dit.generate.GenerateRequest r) ->
                        "gpt-image-2".equals(r.getAdapterKey())
                                && ModelCategory.IMAGE.equalsIgnoreCase(r.getCategory())
                                && "RunningHub".equals(r.getProvider())
                                && "sk-rh".equals(r.getApiKey())));
        verify(chatClient, never()).ping(anyString(), anyString(), anyString());
    }

    @Test
    void testConnectionUsesStoredVideoCredentialsWhenIdProvided() {
        AiModel existing = new AiModel();
        existing.setId(31L);
        existing.setCategory(ModelCategory.VIDEO);
        existing.setAdapterKey("minimax-h3");
        existing.setProvider("官方API");
        existing.setEndpoint("https://api.minimax.cn");
        existing.setModelKey("MiniMax-H3");
        existing.setApiKeyEnc("v1:cipher");
        when(modelMapper.selectById(31L)).thenReturn(existing);
        when(encryptor.decrypt("v1:cipher")).thenReturn("sk-mm");
        cn.aigc.dit.generate.GenerateProvider provider =
                org.mockito.Mockito.mock(cn.aigc.dit.generate.GenerateProvider.class);
        when(providerRegistry.resolveByAdapter("minimax-h3", "官方API",
                ModelCategory.VIDEO, "MiniMax-H3")).thenReturn(provider);

        AdminModelTestConnectionReq req = new AdminModelTestConnectionReq();
        req.setId(31L);
        req.setApiKey("");

        var resp = service.testConnection(req);
        assertEquals("连接成功", resp.getMessage());
        verify(provider).ping(org.mockito.ArgumentMatchers.argThat(
                (cn.aigc.dit.generate.GenerateRequest r) ->
                        "minimax-h3".equals(r.getAdapterKey())
                                && "https://api.minimax.cn".equals(r.getEndpoint())
                                && "sk-mm".equals(r.getApiKey())));
    }

    @Test
    void testConnectionRequiresAdapterKeyForNonText() {
        AdminModelTestConnectionReq req = new AdminModelTestConnectionReq();
        req.setCategory(ModelCategory.IMAGE);
        req.setEndpoint("https://www.runninghub.ai");
        req.setApiKey("sk-rh");
        req.setModelKey("gpt-image-2");

        BizException error = assertThrows(BizException.class, () -> service.testConnection(req));
        assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
        verify(providerRegistry, never()).resolveByAdapter(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testConnectionBubblesUpProviderPingFailure() {
        cn.aigc.dit.generate.GenerateProvider provider =
                org.mockito.Mockito.mock(cn.aigc.dit.generate.GenerateProvider.class);
        when(providerRegistry.resolveByAdapter(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(provider);
        doThrow(new BizException(ErrorCode.BAD_REQUEST, "Ark HTTP 401: 方舟拒绝了 API Key"))
                .when(provider).ping(any());

        AdminModelTestConnectionReq req = new AdminModelTestConnectionReq();
        req.setCategory(ModelCategory.VIDEO);
        req.setAdapterKey("seedance-2.5");
        req.setProvider("官方API");
        req.setEndpoint("https://ark.cn-beijing.volces.com/api/v3");
        req.setApiKey("sk-ark");
        req.setModelKey("doubao-seedance-2-5-260628");

        BizException error = assertThrows(BizException.class, () -> service.testConnection(req));
        assertTrue(error.getMessage().contains("Ark HTTP 401"));
    }

    @Test
    void testConnectionRejectsUnknownAdapterWithoutFallingBackToMock() {
        org.mockito.Mockito.lenient().when(providerRegistry.resolveByAdapter(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "未配置适配协议，请在管理端选择已接入的适配协议"));

        AdminModelTestConnectionReq req = new AdminModelTestConnectionReq();
        req.setCategory(ModelCategory.AUDIO);
        req.setAdapterKey("unknown-voice-clone");
        req.setProvider("官方API");
        req.setEndpoint("https://example.com");
        req.setApiKey("sk");
        req.setModelKey("voice-clone-1");

        BizException error = assertThrows(BizException.class, () -> service.testConnection(req));
        assertTrue(error.getMessage().contains("未配置适配协议"));
    }

    @Test
    void normalizeCategoryAllowsText() {
        assertEquals(ModelCategory.TEXT, AdminModelService.normalizeCategory("TEXT"));
        assertFalse(ModelCategory.isCreatable(ModelCategory.TEXT));
    }
}
