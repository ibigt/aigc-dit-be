package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
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
}
