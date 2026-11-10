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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void processLockedStopsWhenTaskIsCancelledDuringPrepare() {
        GenTask pending = new GenTask();
        pending.setId(8L);
        pending.setUserId(2L);
        pending.setStatus(TaskStatus.PENDING);
        pending.setParams(new java.util.LinkedHashMap<>());
        when(genTaskMapper.selectById(8L)).thenReturn(pending, cancelledTask());

        ReflectionTestUtils.invokeMethod(worker, "processLocked", 8L);

        verify(providerRegistry, never()).resolveByAdapter(any(), any(), any(), any());
        verify(genTaskMapper, never()).updateById(any(GenTask.class));
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
    void providerFailureKeepsActualAttemptCharge() {
        GenTask failed = cancelledTask();
        failed.setStatus(TaskStatus.RUNNING);
        failed.setProviderJobId("provider-job-1");
        when(genTaskMapper.selectById(8L)).thenReturn(failed);

        ReflectionTestUtils.invokeMethod(worker, "finishFailed", failed, "供应商生成失败");

        verify(genTaskMapper).updateById(failed);
        verify(creditService, never()).change(any(), anyLong(), any(), any(), any());
    }

    @Test
    void cancelProviderJobIsNoopWithoutProviderJobId() {
        worker.cancelProviderJob(cancelledTask());
        verify(aiModelMapper, never()).selectById(any());
        verify(providerRegistry, never()).resolveByAdapter(any(), any(), any(), any());
    }

    @Test
    void resumeHealsFrozenRunningTaskWhoseCreationAlreadyLanded() {
        // 进程死在「creation 入库 → 任务写终态」之间：任务停在 RUNNING，但成品已存在。
        // 恢复扫描必须把它补成 SUCCEEDED，否则资产列表永远把已出图资产标成「生成中」。
        GenTask frozen = new GenTask();
        frozen.setId(909L);
        frozen.setUserId(2L);
        frozen.setCategory("image");
        frozen.setStatus(TaskStatus.RUNNING);
        frozen.setProviderJobId("seedream-679f8d93");
        frozen.setProgress(55);
        frozen.setCreatedAt(java.time.OffsetDateTime.now().minusHours(2));
        frozen.setUpdatedAt(java.time.OffsetDateTime.now().minusMinutes(30));
        when(genTaskMapper.selectList(any())).thenReturn(java.util.List.of(frozen));
        when(genTaskMapper.selectById(909L)).thenReturn(frozen);
        when(creationMapper.selectCount(any())).thenReturn(1L);
        Creation landed = new Creation();
        landed.setTaskId(909L);
        landed.setStorageKey("users/7/assets/1683.png");
        landed.setPosterKey("users/7/assets/1683_poster.png");
        landed.setCopyIndex(1);
        when(creationMapper.selectList(any())).thenReturn(java.util.List.of(landed));

        worker.resumeStaleJobs();

        org.mockito.ArgumentCaptor<GenTask> captor = org.mockito.ArgumentCaptor.forClass(GenTask.class);
        verify(genTaskMapper).updateById(captor.capture());
        GenTask healed = captor.getValue();
        assertEquals(TaskStatus.SUCCEEDED, healed.getStatus());
        assertEquals(100, healed.getProgress());
        assertEquals(java.util.List.of("users/7/assets/1683.png"), healed.getOutputKeys());
        assertEquals("users/7/assets/1683.png", healed.getOutputStorageKey());
        assertNotNull(healed.getFinishedAt());
        // 成品已在库，绝不再走厂商轮询。
        verify(providerRegistry, never()).resolve(any());
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
