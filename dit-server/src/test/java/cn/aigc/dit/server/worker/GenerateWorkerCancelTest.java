package cn.aigc.dit.server.worker;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProviderRegistry;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
import cn.aigc.dit.server.drama.DramaShotCaptionService;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.CreationSessionMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateWorkerCancelTest {

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
    }

    @Test
    void processLockedSkipsCancelledTask() {
        when(genTaskMapper.selectById(8L)).thenReturn(cancelledTask());

        ReflectionTestUtils.invokeMethod(worker, "processLocked", 8L);

        verify(aiModelMapper, never()).selectById(any());
        verify(providerRegistry, never()).resolveByAdapter(any(), any(), any(), any());
        verify(genTaskMapper, never()).updateById(any(GenTask.class));
        verify(creditService, never()).change(any(), anyLong(), any(), any(), any());
    }

    @Test
    void finishSuccessDoesNotWriteOutputAfterCancel() {
        when(genTaskMapper.selectById(8L)).thenReturn(cancelledTask());
        GenerateRequest request = new GenerateRequest().setPrompt("夜景");
        PollResult result = PollResult.succeeded("job-1", "users/2/out.mp4");

        ReflectionTestUtils.invokeMethod(worker, "finishSuccess", cancelledTask(), request, result);

        verify(creationMapper, never()).insert(any(Creation.class));
        verify(posterExtractor, never()).extract(any());
        verify(posterExtractor, never()).extractStills(any());
        verify(genTaskMapper, never()).updateById(any(GenTask.class));
        verify(creditService, never()).change(any(), anyLong(), any(), any(), any());
    }

    @Test
    void finishFailedDoesNotRefundAfterCancel() {
        when(genTaskMapper.selectById(8L)).thenReturn(cancelledTask());

        ReflectionTestUtils.invokeMethod(worker, "finishFailed", cancelledTask(), "生成失败");

        verify(genTaskMapper, never()).updateById(any(GenTask.class));
        verify(creditService, never()).change(any(), anyLong(), any(), any(), any());
    }

    @Test
    void cancelProviderJobIsNoopWithoutProviderJobId() {
        worker.cancelProviderJob(cancelledTask());
        verify(aiModelMapper, never()).selectById(any());
        verify(providerRegistry, never()).resolveByAdapter(any(), any(), any(), any());
    }

    private static GenTask cancelledTask() {
        GenTask task = new GenTask();
        task.setId(8L);
        task.setUserId(2L);
        task.setStatus(TaskStatus.CANCELLED);
        task.setCreditCost(40L);
        task.setProgress(0);
        return task;
    }
}
