package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.generate.GenerateTaskPublisher;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateServiceTextModelTest {

    @Test
    void audioCopiesSurviveRealSubmissionNormalizationAndBilling() {
        AiModel model = new AiModel(); model.setId(12L); model.setEnabled(true); model.setName("Mock Audio");
        model.setCategory("audio"); model.setAdapterKey("mock"); model.setModelKey("mock-audio");
        model.setPricing(Map.of("unit", "次", "sale", Map.of("output", 5)));
        when(aiModelMapper.selectById(12L)).thenReturn(model);
        java.util.concurrent.atomic.AtomicReference<GenTask> saved = new java.util.concurrent.atomic.AtomicReference<>();
        when(genTaskMapper.insert(any(GenTask.class))).thenAnswer(call -> { GenTask task = call.getArgument(0); task.setId(88L); saved.set(task); return 1; });
        GenerateSubmitReq req = new GenerateSubmitReq(); req.setModelId(12L); req.setCategory("audio"); req.setPrompt("hello"); req.setCopies(3);
        service.submit(req, Map.of("source", GenerateRequests.SOURCE_DRAMA));
        assertEquals(3, saved.get().getCopies());
        assertEquals(3, saved.get().getParams().get("copies"));
        assertEquals(15L, saved.get().getCreditCost());
        var providerRequest = GenerateRequests.fromTask(saved.get(), "mock-audio");
        assertEquals(3, providerRequest.resolvedCopies());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 5})
    void rejectsOutOfRangeAudioCopiesBeforeCharging(int copies) {
        AiModel model = new AiModel(); model.setId(12L); model.setEnabled(true); model.setName("Mock Audio");
        model.setCategory("audio"); model.setAdapterKey("mock"); model.setModelKey("mock-audio");
        when(aiModelMapper.selectById(12L)).thenReturn(model);
        GenerateSubmitReq req = new GenerateSubmitReq(); req.setModelId(12L); req.setCategory("audio"); req.setPrompt("hello"); req.setCopies(copies);
        assertThrows(BizException.class, () -> service.submit(req));
    }

    @Test
    void rejectsMusicForSpeechOnlyAdapterBeforeBilling() {
        AiModel model = new AiModel(); model.setId(12L); model.setEnabled(true); model.setName("Speech");
        model.setCategory("audio"); model.setAdapterKey("openai-speech"); model.setModelKey("tts-1");
        when(aiModelMapper.selectById(12L)).thenReturn(model);
        GenerateSubmitReq req = new GenerateSubmitReq(); req.setModelId(12L); req.setCategory("audio"); req.setPrompt("strings"); req.setAudioMode("music");
        BizException error = assertThrows(BizException.class, () -> service.submit(req));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("音乐"));
    }

    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private CreationMapper creationMapper;
    @Mock
    private CreationService creationService;
    @Mock
    private AssetService assetService;
    @Mock
    private CreditService creditService;
    @Mock
    private GenerateTaskPublisher publisher;
    @Mock
    private ObjectProvider<cn.aigc.dit.server.worker.GenerateWorker> generateWorker;
    @Mock
    private Executor generateKickExecutor;
    @Mock
    private GenTaskMapper genTaskMapper;

    private GenerateService service;

    @BeforeEach
    void setUp() {
        service = new GenerateService(
                aiModelMapper,
                creationMapper,
                creationService,
                assetService,
                creditService,
                publisher,
                generateWorker,
                generateKickExecutor);
        ReflectionTestUtils.setField(service, "baseMapper", genTaskMapper);
        UserContext.set(new LoginUser(1L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void submitRejectsEnabledTextModel() {
        AiModel model = new AiModel();
        model.setId(9L);
        model.setEnabled(true);
        model.setCategory(ModelCategory.TEXT);
        model.setAdapterKey("agnes-text");
        when(aiModelMapper.selectById(9L)).thenReturn(model);

        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(9L);
        req.setCategory(ModelCategory.IMAGE);
        req.setPrompt("洛白全身参考图");

        BizException error = assertThrows(BizException.class, () -> service.submit(req));
        assertEquals("该模型不能用于快速创作", error.getMessage());
    }

    @Test
    void dramaSubmitDoesNotCreateQuickCreateSession() {
        AiModel model = new AiModel();
        model.setId(12L);
        model.setEnabled(true);
        model.setName("Mock Image");
        model.setModelKey("mock-image");
        model.setAdapterKey("mock");
        model.setCategory(ModelCategory.IMAGE);
        model.setCapabilities(List.of("image", "t2i"));
        when(aiModelMapper.selectById(12L)).thenReturn(model);
        when(genTaskMapper.insert(any(GenTask.class))).thenAnswer(invocation -> {
            GenTask task = invocation.getArgument(0);
            task.setId(88L);
            return 1;
        });
        when(creationService.toTaskResp(any(), any(), any())).thenAnswer(invocation -> {
            GenTask task = invocation.getArgument(0);
            GenerateTaskResp resp = new GenerateTaskResp();
            resp.setId(task.getId());
            resp.setParams(task.getParams());
            return resp;
        });

        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(12L);
        req.setCategory(ModelCategory.IMAGE);
        req.setPrompt("洛白角色设定图");
        req.setCopies(1);

        GenerateTaskResp resp = service.submit(req, Map.of(
                "source", GenerateRequests.SOURCE_DRAMA,
                "assetId", 9L));

        verify(creationService, never()).ensureSession(
                nullable(Long.class), nullable(Long.class), nullable(String.class), nullable(String.class));
        ArgumentCaptor<GenTask> captor = ArgumentCaptor.forClass(GenTask.class);
        verify(genTaskMapper).insert(captor.capture());
        assertEquals(GenerateRequests.SOURCE_DRAMA, captor.getValue().getParams().get("source"));
        assertNull(captor.getValue().getParams().get("sessionId"));
        assertEquals(9L, captor.getValue().getParams().get("assetId"));
        assertEquals(88L, resp.getId());
    }

    @Test
    void dramaI2vFirstFrameOnlyAllowedWhenAdapterSupports() {
        AiModel model = new AiModel();
        model.setId(88L);
        model.setEnabled(true);
        model.setName("Seedance 2.0 Fast");
        model.setModelKey("doubao-seedance-2.0-fast");
        model.setAdapterKey("seedance-2.0");
        model.setProvider("官方API");
        model.setCategory(ModelCategory.VIDEO);
        model.setCapabilities(List.of("video", "t2v", "r2v"));
        when(aiModelMapper.selectById(88L)).thenReturn(model);
        when(genTaskMapper.insert(any(GenTask.class))).thenAnswer(invocation -> {
            GenTask task = invocation.getArgument(0);
            task.setId(91L);
            return 1;
        });
        when(creationService.toTaskResp(any(), any(), any())).thenAnswer(invocation -> {
            GenTask task = invocation.getArgument(0);
            GenerateTaskResp resp = new GenerateTaskResp();
            resp.setId(task.getId());
            resp.setParams(task.getParams());
            return resp;
        });

        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(88L);
        req.setCategory(ModelCategory.VIDEO);
        req.setPrompt("从首帧起幅，局长推门进来");
        req.setDurationSec(5);
        GenerateSubmitReq.FrameInput frames = new GenerateSubmitReq.FrameInput();
        frames.setFirstFrameKey("users/1/drama/21/frames/a.webp");
        req.setFrames(frames);

        GenerateTaskResp resp = service.submit(req, Map.of(
                "source", GenerateRequests.SOURCE_DRAMA,
                "shotId", 201L));

        ArgumentCaptor<GenTask> captor = ArgumentCaptor.forClass(GenTask.class);
        verify(genTaskMapper).insert(captor.capture());
        assertEquals(GenerateMode.I2V, captor.getValue().getParams().get("mode"));
        assertEquals("users/1/drama/21/frames/a.webp",
                ((java.util.Map<?, ?>) captor.getValue().getParams().get("frames")).get("firstFrameKey"));
        assertEquals(91L, resp.getId());
    }
}
