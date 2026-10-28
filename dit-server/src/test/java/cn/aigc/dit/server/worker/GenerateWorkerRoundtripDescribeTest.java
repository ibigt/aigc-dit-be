package cn.aigc.dit.server.worker;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProviderRegistry;
import cn.aigc.dit.generate.describe.ImageDescribeClient;
import cn.aigc.dit.generate.describe.ImageDescribePrompts;
import cn.aigc.dit.generate.describe.ImageDescribeResult;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.drama.DramaAssetService;
import cn.aigc.dit.server.drama.DramaShotCaptionService;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.CreationSessionMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.service.CreditService;
import cn.aigc.dit.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateWorkerRoundtripDescribeTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private CreationMapper creationMapper;
    @Mock
    private CreationSessionMapper creationSessionMapper;
    @Mock
    private GenerateProviderRegistry providerRegistry;
    @Mock
    private PosterExtractor posterExtractor;
    @Mock
    private DramaShotCaptionService dramaShotCaptionService;
    @Mock
    private CreditService creditService;
    @Mock
    private AesGcmEncryptor encryptor;
    @Mock
    private ImageDescribeClient imageDescribeClient;
    @Mock
    private ObjectStorage objectStorage;

    private GenerateWorker worker;

    @BeforeEach
    void setUp() {
        worker = new GenerateWorker(
                stringRedisTemplate,
                new GenerateProperties(),
                genTaskMapper,
                aiModelMapper,
                creationMapper,
                creationSessionMapper,
                providerRegistry,
                posterExtractor,
                dramaShotCaptionService,
                creditService,
                encryptor);
        ReflectionTestUtils.setField(worker, "imageDescribeClient", imageDescribeClient);
        ReflectionTestUtils.setField(worker, "objectStorage", objectStorage);
    }

    @Test
    void resolvePendingRoundtripDescribeFillsPromptAndClearsPending() {
        GenTask task = pendingDescribeTask();
        AiModel model = seedreamModel();
        when(encryptor.decrypt("enc-key")).thenReturn("ark-test-key");
        when(objectStorage.getBytes("users/7/upload/ref.png")).thenReturn(new byte[]{1, 2, 3});
        when(imageDescribeClient.describe(any(), eq("image/png"),
                eq(ImageDescribePrompts.CHARACTER_FULL), anyString(), eq("ark-test-key"), isNull()))
                .thenReturn(new ImageDescribeResult(
                        "中年男性短发",
                        "方脸浓眉",
                        "【identity】中年男性短发\n【t2i_prompt】方脸浓眉"));

        worker.resolvePendingRoundtripDescribe(task, model);

        assertTrue(task.getPrompt().contains("中年男性短发"));
        assertTrue(task.getPrompt().contains("方脸浓眉"));
        assertEquals(task.getPrompt(), task.getParams().get("roundtripPrompt"));
        assertFalse(task.getParams().containsKey(DramaAssetService.PARAM_ROUNDTRIP_DESCRIBE_PENDING));
        ArgumentCaptor<GenTask> captor = ArgumentCaptor.forClass(GenTask.class);
        verify(genTaskMapper).updateById(captor.capture());
        assertEquals(task.getPrompt(), captor.getValue().getPrompt());
    }

    @Test
    void resolvePendingRoundtripDescribeUsesScenePrompt() {
        GenTask task = pendingDescribeTask();
        task.getParams().put(DramaAssetService.PARAM_ROUNDTRIP_DESCRIBE_ASSET_TYPE, "场景");
        task.getParams().put(DramaAssetService.PARAM_ROUNDTRIP_DESCRIBE_STORAGE_KEY, "users/7/upload/scene.jpg");
        AiModel model = seedreamModel();
        when(encryptor.decrypt("enc-key")).thenReturn("ark-test-key");
        when(objectStorage.getBytes("users/7/upload/scene.jpg")).thenReturn(new byte[]{9});
        when(imageDescribeClient.describe(any(), eq("image/jpeg"),
                eq(ImageDescribePrompts.SCENE), anyString(), eq("ark-test-key"), isNull()))
                .thenReturn(new ImageDescribeResult("走廊", "冷光", "【identity】走廊\n【t2i_prompt】冷光"));

        worker.resolvePendingRoundtripDescribe(task, model);

        verify(imageDescribeClient).describe(any(), eq("image/jpeg"),
                eq(ImageDescribePrompts.SCENE), anyString(), eq("ark-test-key"), isNull());
        assertTrue(task.getPrompt().contains("走廊"));
    }

    @Test
    void resolvePendingRoundtripDescribeFailsWhenVisionEmpty() {
        GenTask task = pendingDescribeTask();
        AiModel model = seedreamModel();
        when(encryptor.decrypt("enc-key")).thenReturn("ark-test-key");
        when(objectStorage.getBytes("users/7/upload/ref.png")).thenReturn(new byte[]{1});
        when(imageDescribeClient.describe(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ImageDescribeResult("", "", ""));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> worker.resolvePendingRoundtripDescribe(task, model));
        assertEquals("图生文未得到可用提示词", error.getMessage());
    }

    private static GenTask pendingDescribeTask() {
        GenTask task = new GenTask();
        task.setId(88L);
        task.setPrompt(DramaAssetService.PENDING_DESCRIBE_PROMPT);
        task.setProgress(0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(DramaAssetService.PARAM_ROUNDTRIP_DESCRIBE_PENDING, true);
        params.put(DramaAssetService.PARAM_ROUNDTRIP_DESCRIBE_STORAGE_KEY, "users/7/upload/ref.png");
        params.put(DramaAssetService.PARAM_ROUNDTRIP_DESCRIBE_ASSET_TYPE, "角色");
        params.put("assetGenerationSource", DramaAssetService.SOURCE_ROUNDTRIP);
        task.setParams(params);
        return task;
    }

    private static AiModel seedreamModel() {
        AiModel model = new AiModel();
        model.setId(12L);
        model.setCategory("image");
        model.setProvider("官方API");
        model.setEndpoint("https://ark.cn-beijing.volces.com/api/v3");
        model.setApiKeyEnc("enc-key");
        model.setAdapterKey("seedream-5.0");
        model.setModelKey("doubao-seedream-5-0-260128");
        return model;
    }
}
