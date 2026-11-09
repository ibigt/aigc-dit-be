package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.entity.WorkflowRun;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.WorkflowRunMapper;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.model.resp.WorkflowRunResp;
import cn.aigc.dit.server.model.req.WorkflowRunReq;
import cn.aigc.dit.server.workflow.FfmpegWorkflowFilmComposer;
import cn.aigc.dit.server.workflow.WorkflowFilmComposer;
import cn.aigc.dit.storage.LocalObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRunServiceTest {

    @TempDir private Path directory;

    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private GenTaskMapper genTaskMapper;
    @Mock private AiModelMapper aiModelMapper;
    @Mock private GenerateService generateService;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowFilmComposer filmComposer;
    @Mock private CreationService creationService;
    @Mock private cn.aigc.dit.storage.ObjectStorage objectStorage;
    private final WorkflowRunLock workflowRunLock = runId -> () -> { };

    private WorkflowRunService service;

    @BeforeEach
    void setUp() {
        lenient().when(objectStorage.exists(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service = new WorkflowRunService(workflowRunMapper, genTaskMapper, aiModelMapper,
                generateService, workflowService, workflowRunLock, filmComposer, creationService, objectStorage);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "Creator", LoginUser.ROLE_CREATOR));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void hydratesEverySuccessfulTaskOutputAndCost() {
        WorkflowRun run = run(step("video-a", TaskStatus.RUNNING, 11L, List.of()));
        GenTask task = new GenTask();
        task.setId(11L);
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setCategory("video");
        task.setOutputKeys(List.of("one.mp4", "two.mp4"));
        task.setPosterKey("poster.jpg");
        task.setProgress(100);
        task.setCreditCost(48L);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(genTaskMapper.selectById(11L)).thenReturn(task);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.SUCCEEDED, response.getSteps().get(0).getStatus());
        assertEquals(2, response.getSteps().get(0).getOutputs().size());
        assertEquals("/api/media?key=one.mp4", response.getSteps().get(0).getOutputs().get(0).getUrl());
        assertEquals("/api/media?key=poster.jpg", response.getSteps().get(0).getOutputs().get(0).getPosterUrl());
        assertEquals(48L, response.getSteps().get(0).getCreditCost());
        assertEquals(100, response.getSteps().get(0).getProgress());
    }

    @Test
    void fallsBackToLegacyOutputKeyAndHydratesAvailableMetadata() {
        WorkflowRun run = run(step("video-a", TaskStatus.RUNNING, 11L, List.of()));
        GenTask task = new GenTask();
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setOutputStorageKey("legacy.mp4");
        task.setParams(Map.of(
                "source", "quick-create",
                "mode", "text-to-video",
                "ratio", "16:9",
                "resolution", "1080p",
                "durationSec", 5,
                "copies", 2,
                "metadata", Map.of("durationMs", 1234)));
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(genTaskMapper.selectById(11L)).thenReturn(task);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(1, response.getSteps().get(0).getOutputs().size());
        assertEquals("legacy.mp4", response.getSteps().get(0).getOutputs().get(0).getStorageKey());
        assertEquals(Map.of("ratio", "16:9", "resolution", "1080p", "durationSec", 5,
                        "copies", 2, "durationMs", 1234),
                response.getSteps().get(0).getOutputs().get(0).getMetadata());
    }

    @Test
    void completedLegacyRunSynthesizesOutputWithoutRefreshingTask() {
        WorkflowRun run = run(step("video-a", TaskStatus.SUCCEEDED, 11L, List.of()));
        run.setStatus(TaskStatus.SUCCEEDED);
        run.getSteps().get(0).put("outputStorageKey", "old.mp4");
        run.getSteps().get(0).put("posterKey", "old-poster.jpg");
        when(workflowRunMapper.selectById(1L)).thenReturn(run);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(1, response.getSteps().get(0).getOutputs().size());
        assertEquals("video", response.getSteps().get(0).getOutputs().get(0).getType());
        assertEquals("old.mp4", response.getSteps().get(0).getOutputs().get(0).getStorageKey());
        assertEquals("/api/media?key=old.mp4", response.getSteps().get(0).getOutputs().get(0).getUrl());
        assertEquals("/api/media?key=old-poster.jpg", response.getSteps().get(0).getOutputs().get(0).getPosterUrl());
    }

    @Test
    void failedBranchBlocksDependentsWhileIndependentBranchFinishes() {
        WorkflowRun run = run(
                step("video-a", TaskStatus.RUNNING, 11L, List.of()),
                step("edit", TaskStatus.PENDING, null, List.of("video-a")),
                step("video-b", TaskStatus.PENDING, null, List.of()));
        run.getSteps().get(1).put("stepType", "EDIT");
        run.getSteps().get(1).put("category", null);
        run.getSteps().get(2).put("modelId", 2L);
        run.getSteps().get(2).put("prompt", "A mountain");
        GenTask failed = new GenTask();
        failed.setStatus(TaskStatus.FAILED);
        failed.setErrorMessage("provider failed");
        GenTask succeeded = new GenTask();
        succeeded.setStatus(TaskStatus.SUCCEEDED);
        succeeded.setOutputStorageKey("video-b.mp4");
        succeeded.setProgress(100);
        GenerateTaskResp submitted = new GenerateTaskResp();
        submitted.setId(22L);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(genTaskMapper.selectById(11L)).thenReturn(failed);
        when(generateService.submit(any(), any())).thenReturn(submitted);

        WorkflowRunResp first = service.getMine(1L);

        assertEquals(TaskStatus.RUNNING, first.getStatus());
        assertEquals(TaskStatus.FAILED, first.getSteps().get(0).getStatus());
        assertEquals("BLOCKED", first.getSteps().get(1).getStatus());
        assertEquals(TaskStatus.RUNNING, first.getSteps().get(2).getStatus());
        assertEquals(22L, first.getSteps().get(2).getTaskId());

        when(genTaskMapper.selectById(22L)).thenReturn(succeeded);
        WorkflowRunResp second = service.getMine(1L);

        assertEquals("PARTIAL_FAILED", second.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, second.getSteps().get(2).getStatus());
        assertNotNull(second.getFinishedAt());
        assertEquals("provider failed", second.getErrorMessage());
    }

    @Test
    void cancelMarksStepCancelledInsteadOfFailed() {
        WorkflowRun run = run(step("video-a", TaskStatus.RUNNING, 11L, List.of()));
        when(workflowRunMapper.selectById(1L)).thenReturn(run);

        WorkflowRunResp response = service.cancelMine(1L, null);

        // 用户主动停止不是生成失败：step 与运行终态都记 CANCELLED，前端才能显示「已取消」而非「生成失败」
        assertEquals(TaskStatus.CANCELLED, response.getStatus());
        assertEquals(TaskStatus.CANCELLED, response.getSteps().get(0).getStatus());
        assertEquals("已取消", response.getSteps().get(0).getErrorMessage());
        verify(generateService).cancelTaskIfActive(11L, "取消工作流退还积分");
    }

    @Test
    void cancelledTaskKeepsCancelledStatusWhenRunConverges() {
        WorkflowRun run = run(step("video-a", TaskStatus.RUNNING, 11L, List.of()));
        GenTask cancelled = new GenTask();
        cancelled.setStatus(TaskStatus.CANCELLED);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(genTaskMapper.selectById(11L)).thenReturn(cancelled);

        WorkflowRunResp response = service.getMine(1L);

        // tick 收敛时不能把已取消的 task 改写成 FAILED，否则又变回「生成失败」
        assertEquals(TaskStatus.CANCELLED, response.getSteps().get(0).getStatus());
        assertEquals(TaskStatus.CANCELLED, response.getStatus());
    }

    @Test
    void stuckStepWithoutTaskIsFailedSoRunStopsHoldingTheStopButton() {
        // 步骤卡在 SUBMITTING 且始终拿不到 taskId：旧逻辑下 run 会永远 RUNNING，
        // 前端刷新后右上角一直显示「停止」。现在超时即判 FAILED 并收敛终态。
        Map<String, Object> step = step("video-a", "SUBMITTING", null, List.of());
        step.put("stuckSince", System.currentTimeMillis() - 10 * 60_000L);
        WorkflowRun run = run(step);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.FAILED, response.getSteps().get(0).getStatus());
        assertEquals("生成任务提交超时，请重试", response.getSteps().get(0).getErrorMessage());
        assertNotEquals(TaskStatus.RUNNING, response.getStatus());
    }

    @Test
    void freshStuckStepIsGivenTimeBeforeBeingFailed() {
        // 刚进入中间态就判失败会误伤正常提交，必须先记下 stuckSince 再说
        Map<String, Object> step = step("video-a", "SUBMITTING", null, List.of());
        step.put("stuckSince", System.currentTimeMillis());
        WorkflowRun run = run(step);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);

        WorkflowRunResp response = service.getMine(1L);

        assertNotEquals(TaskStatus.FAILED, response.getSteps().get(0).getStatus());
    }

    @Test
    void runOlderThanHalfAnHourIsForcedToTerminal() {
        // 运行级兜底：任何原因导致的坏死运行都不能永久占住 activeRuns
        Map<String, Object> step = step("video-a", TaskStatus.RUNNING, 11L, List.of());
        WorkflowRun run = run(step);
        run.setCreatedAt(java.time.OffsetDateTime.now().minusMinutes(40));
        GenTask running = new GenTask();
        running.setStatus(TaskStatus.RUNNING);
        running.setProgress(30);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(genTaskMapper.selectById(11L)).thenReturn(running);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals("PARTIAL_FAILED", response.getStatus());
        assertEquals("运行超时未完成", response.getErrorMessage());
        assertEquals(TaskStatus.FAILED, response.getSteps().get(0).getStatus());
    }

    @Test
    void textOnlyRunIsRejectedWithoutRunnableGenerateNode() {
        WorkflowRunReq request = new WorkflowRunReq();
        request.setGraph(Map.of(
                "nodes", List.of(Map.of("id", "brief", "data", Map.of(
                        "kind", "MarkdownNote", "title", "Brief", "enabled", true,
                        "fields", List.of(Map.of("label", "内容", "value", "Rainy night"))))),
                "edges", List.of()));
        when(aiModelMapper.selectList(any())).thenReturn(List.of());

        // 纯文本画布没有任何 GENERATE/EDIT 步骤：不再创建「0 分母、瞬间成功」的空运行，直接拦截
        BizException error = assertThrows(BizException.class, () -> service.start(request));
        assertTrue(error.getMessage().contains("本次运行没有可执行的生成节点"), error.getMessage());
        verify(workflowRunMapper, never()).insert(any(WorkflowRun.class));
        verify(generateService, never()).submit(any(), any());
    }

    @Test
    void wholeGraphRunReusesUnchangedStepFromHistory() {
        runStore();
        when(aiModelMapper.selectList(any())).thenReturn(List.of(imageModel()));
        when(generateService.submit(any(), any())).thenReturn(submittedTask());
        WorkflowRunReq request = new WorkflowRunReq();
        request.setWorkflowId(5L);
        request.setGraph(imageGraph());

        service.start(request);
        succeedTask(31L);
        service.getMine(1L);

        WorkflowRunResp second = service.start(request);

        assertEquals(TaskStatus.SUCCEEDED, second.getStatus());
        assertTrue(second.getSteps().get(0).getReused());
        assertEquals(0L, second.getSteps().get(0).getCreditCost());
        assertNotNull(second.getSteps().get(0).getOutputs().get(0).getStorageKey());
        verify(generateService, times(1)).submit(any(), any());
    }

    @Test
    void forceRegenerateRunsEveryStepEvenWhenHistoryMatches() {
        runStore();
        when(aiModelMapper.selectList(any())).thenReturn(List.of(imageModel()));
        when(generateService.submit(any(), any())).thenReturn(submittedTask());
        WorkflowRunReq request = new WorkflowRunReq();
        request.setWorkflowId(5L);
        request.setGraph(imageGraph());

        service.start(request);
        succeedTask(31L);
        service.getMine(1L);
        request.setForceRegenerate(true);

        WorkflowRunResp second = service.start(request);

        assertEquals(TaskStatus.RUNNING, second.getStatus());
        assertEquals(Boolean.FALSE, second.getSteps().get(0).getReused());
        assertEquals(TaskStatus.RUNNING, second.getSteps().get(0).getStatus());
        verify(generateService, times(2)).submit(any(), any());
    }

    @Test
    void runTotalCountsOnlyWorkStepsSoProgressMatchesRealWork() {
        runStore();
        when(aiModelMapper.selectList(any())).thenReturn(List.of(imageModel()));
        when(generateService.submit(any(), any())).thenReturn(submittedTask());
        WorkflowRunReq request = new WorkflowRunReq();
        request.setGraph(Map.of(
                "nodes", List.of(
                        Map.of("id", "brief", "data", Map.of(
                                "kind", "MarkdownNote", "title", "Brief", "enabled", true,
                                "fields", List.of(Map.of("label", "内容", "value", "Rainy night")))),
                        Map.of("id", "image-a", "data", Map.of(
                                "kind", "ImageGenerator", "title", "Image A", "enabled", true,
                                "media", Map.of("prompt", "a cat", "model", "demo-image")))),
                "edges", List.of()));

        WorkflowRunResp response = service.start(request);

        // 文本节点是零成本直通步骤，不计入进度分母：只显示真正干活的 1 个生成节点
        assertEquals(1, response.getTotal());
        assertEquals(2, response.getSteps().size());
    }

    @Test
    void pendingStepWithPersistedTaskIdIsNotSubmittedAgain() {
        WorkflowRun run = run(step("video-b", TaskStatus.PENDING, 22L, List.of()));
        when(workflowRunMapper.selectById(1L)).thenReturn(run);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.RUNNING, response.getSteps().get(0).getStatus());
        assertEquals(22L, response.getSteps().get(0).getTaskId());
        verify(generateService, never()).submit(any(), any());
    }

    @Test
    void downstreamRequestUsesSelectedCopyAndFallsBackToFirstInsteadOfLegacyKey() {
        Map<String, Object> source = step("source", TaskStatus.SUCCEEDED, 11L, List.of());
        source.put("outputIndex", 1);
        source.put("outputStorageKey", "users/7/stale.mp4");
        source.put("outputs", List.of(videoOutput("users/7/first.mp4", 5), videoOutput("users/7/selected.mp4", 5)));
        Map<String, Object> target = step("target", TaskStatus.PENDING, null, List.of("source"));
        target.put("prompt", "film");
        target.put("modelId", 2L);
        WorkflowRun run = run(source, target);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        List<String> received = new ArrayList<>();
        when(generateService.submit(any(), any())).thenAnswer(invocation -> {
            cn.aigc.dit.server.model.req.GenerateSubmitReq request = invocation.getArgument(0);
            received.add(request.getReferences().get(0).getStorageKey());
            GenerateTaskResp response = new GenerateTaskResp();
            response.setId(22L);
            response.setCreditCost(24L);
            return response;
        });
        WorkflowRunResp response = service.getMine(1L);
        assertEquals(List.of("users/7/selected.mp4"), received);
        assertEquals(24L, response.getSteps().get(1).getCreditCost());
        run.getSteps().get(0).put("outputIndex", 99);
        run.getSteps().get(1).put("status", TaskStatus.PENDING);
        run.getSteps().get(1).remove("taskId");
        service.getMine(1L);
        assertEquals(List.of("users/7/selected.mp4", "users/7/first.mp4"), received);
    }

    @Test
    void frameModeInfersFirstFrameWhenOnlyLastFrameIsExplicit() {
        Map<String, Object> source = step("source", TaskStatus.SUCCEEDED, 11L, List.of());
        source.put("outputStorageKey", "users/7/input.mp4");
        Map<String, Object> target = step("target", TaskStatus.PENDING, null, List.of("source"));
        target.put("prompt", "film");
        target.put("modelId", 2L);
        target.put("frameMode", true);
        target.put("videoRefMode", false);
        target.put("lastFrameKey", "users/7/last.jpg");
        WorkflowRun run = run(source, target);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        List<GenerateSubmitReq.FrameInput> frames = new ArrayList<>();
        List<Object> references = new ArrayList<>();
        when(generateService.submit(any(), any())).thenAnswer(invocation -> {
            GenerateSubmitReq request = invocation.getArgument(0);
            frames.add(request.getFrames());
            references.add(request.getReferences());
            GenerateTaskResp response = new GenerateTaskResp();
            response.setId(22L);
            return response;
        });

        service.getMine(1L);

        assertEquals("users/7/input-tail.webp", frames.get(0).getFirstFrameKey());
        assertEquals("users/7/last.jpg", frames.get(0).getLastFrameKey());
        assertNull(references.get(0));
    }

    @Test
    void referenceImageCountOverLimitIsRejected() {
        List<Map<String, Object>> staticRefs = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) {
            staticRefs.add(Map.of("type", "image", "storageKey", "users/7/ref-" + i + ".png"));
        }
        Map<String, Object> target = step("image-node", TaskStatus.PENDING, null, List.of());
        target.put("category", "image");
        target.put("prompt", "style");
        target.put("modelId", 2L);
        target.put("staticRefs", staticRefs);
        WorkflowRun run = run(target);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenReturn(1);

        service.getMine(1L);

        assertEquals(TaskStatus.FAILED, run.getSteps().get(0).get("status"));
        assertTrue(String.valueOf(run.getSteps().get(0).get("error")).contains("参考图片 31 张超过上限 30"));
    }

    @Test
    void referenceVideoCountOverLimitIsRejected() {
        List<Map<String, Object>> staticRefs = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            staticRefs.add(Map.of("type", "video", "storageKey", "users/7/ref-" + i + ".mp4"));
        }
        Map<String, Object> target = step("video-node", TaskStatus.PENDING, null, List.of());
        target.put("category", "video");
        target.put("prompt", "transfer");
        target.put("modelId", 2L);
        target.put("staticRefs", staticRefs);
        WorkflowRun run = run(target);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenReturn(1);

        service.getMine(1L);

        assertEquals(TaskStatus.FAILED, run.getSteps().get(0).get("status"));
        assertTrue(String.valueOf(run.getSteps().get(0).get("error")).contains("参考视频 11 段超过上限 10"));
    }

    @Test
    void seedance20ReferenceVideoCountUsesTighterLimit() {
        List<Map<String, Object>> staticRefs = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            staticRefs.add(Map.of("type", "video", "storageKey", "users/7/ref-" + i + ".mp4"));
        }
        Map<String, Object> target = step("video-node", TaskStatus.PENDING, null, List.of());
        target.put("prompt", "transfer");
        target.put("modelId", 2L);
        target.put("staticRefs", staticRefs);
        WorkflowRun run = run(target);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenReturn(1);
        when(aiModelMapper.selectById(2L)).thenReturn(seedance20Model());

        service.getMine(1L);

        assertEquals(TaskStatus.FAILED, run.getSteps().get(0).get("status"));
        assertTrue(String.valueOf(run.getSteps().get(0).get("error")).contains("参考视频 4 段超过上限 3"));
    }

    @Test
    void seedance20ReferenceVideoSecondsUseTighterLimit() {
        Map<String, Object> first = step("source-1", TaskStatus.SUCCEEDED, 11L, List.of());
        first.put("outputs", List.of(Map.of(
                "type", "video", "storageKey", "users/7/a.mp4", "metadata", Map.of("durationSec", 8D))));
        Map<String, Object> second = step("source-2", TaskStatus.SUCCEEDED, 12L, List.of());
        second.put("outputs", List.of(Map.of(
                "type", "video", "storageKey", "users/7/b.mp4", "metadata", Map.of("durationSec", 8D))));
        Map<String, Object> target = step("video-node", TaskStatus.PENDING, null, List.of("source-1", "source-2"));
        target.put("prompt", "transfer");
        target.put("modelId", 2L);
        WorkflowRun run = run(first, second, target);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenReturn(1);
        when(aiModelMapper.selectById(2L)).thenReturn(seedance20Model());

        service.getMine(1L);

        assertEquals(TaskStatus.FAILED, run.getSteps().get(2).get("status"));
        assertTrue(String.valueOf(run.getSteps().get(2).get("error")).contains("总时长 16 秒超过上限 15 秒"));
    }

    private static AiModel seedance20Model() {
        AiModel model = new AiModel();
        model.setId(2L);
        model.setName("Seedance 2.0");
        model.setCategory("video");
        model.setAdapterKey("seedance-2.0");
        model.setModelKey("doubao-seedance-2-0-260128");
        return model;
    }

    @Test
    void referenceAudioCountOverLimitIsRejected() {
        List<Map<String, Object>> staticRefs = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            staticRefs.add(Map.of("type", "audio", "storageKey", "users/7/ref-" + i + ".mp3"));
        }
        Map<String, Object> target = step("video-node", TaskStatus.PENDING, null, List.of());
        target.put("category", "video");
        target.put("prompt", "voiceover video");
        target.put("modelId", 2L);
        target.put("staticRefs", staticRefs);
        WorkflowRun run = run(target);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenReturn(1);

        service.getMine(1L);

        assertEquals(TaskStatus.FAILED, run.getSteps().get(0).get("status"));
        assertTrue(String.valueOf(run.getSteps().get(0).get("error")).contains("参考音频 11 条超过上限 10"));
    }

    @Test
    void graphAudioParametersReachCanonicalTaskParamsAndProviderRequest() {
        for (String tab : List.of("speech", "music")) {
            AiModel model = new AiModel();
            model.setId(2L); model.setName("Audio"); model.setCategory("audio");
            when(aiModelMapper.selectList(any())).thenReturn(List.of(model));
            AtomicReference<WorkflowRun> durable = new AtomicReference<>();
            when(workflowRunMapper.insert(any(WorkflowRun.class))).thenAnswer(call -> {
                WorkflowRun run = call.getArgument(0); run.setId(1L); durable.set(run); return 1;
            });
            when(workflowRunMapper.selectById(1L)).thenAnswer(call -> durable.get());
            List<Map<String, Object>> params = new ArrayList<>();
            org.mockito.Mockito.doAnswer(call -> {
                cn.aigc.dit.server.model.req.GenerateSubmitReq req = call.getArgument(0);
                var canonical = cn.aigc.dit.server.generate.GenerateRequests.fromSubmit(7L, "mock-audio", req, null);
                GenTask task = new GenTask(); task.setCategory("audio"); task.setPrompt(canonical.getPrompt());
                task.setParams(cn.aigc.dit.server.generate.GenerateRequests.toParams(canonical, null));
                var restored = cn.aigc.dit.server.generate.GenerateRequests.fromTask(task, "mock-audio");
                params.add(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(restored, Map.class));
                GenerateTaskResp response = new GenerateTaskResp(); response.setId(22L); return response;
            }).when(generateService).submit(any(), any());
            WorkflowRunReq request = new WorkflowRunReq(); request.setModelId(2L);
            request.setGraph(Map.of("nodes", List.of(Map.of("id", "audio", "data", Map.of("kind", "AudioGenerator",
                    "media", Map.of("model", "Audio", "prompt", "warm strings", "tab", tab, "voice", "nova",
                            "emotion", "happy", "speed", 1.2, "lyrics", "hello", "instrumental", false, "copies", 3)))), "edges", List.of()));
            service.start(request);
            assertEquals("nova", params.get(0).get("voiceId"));
            assertEquals("happy", params.get(0).get("emotion"));
            assertEquals(tab, params.get(0).get("audioMode"));
            assertEquals("warm strings", params.get(0).get("prompt"));
            assertEquals("hello", params.get(0).get("lyrics"));
            assertEquals(false, params.get(0).get("instrumental"));
            assertEquals(1.2, params.get(0).get("speed"));
            assertEquals(3, params.get(0).get("copies"));
        }
    }

    @Test
    void selectedUploadedAudioIsDurableTypedOutputAndDownstreamReference() {
        AiModel model = new AiModel(); model.setId(2L); model.setName("Video"); model.setCategory("video");
        when(aiModelMapper.selectList(any())).thenReturn(List.of(model));
        AtomicReference<WorkflowRun> durable = new AtomicReference<>();
        when(workflowRunMapper.insert(any(WorkflowRun.class))).thenAnswer(call -> {
            WorkflowRun run = call.getArgument(0); run.setId(1L); durable.set(run); return 1;
        });
        when(workflowRunMapper.selectById(1L)).thenAnswer(call -> durable.get());
        List<String> refs = new ArrayList<>();
        when(generateService.submit(any(), any())).thenAnswer(call -> {
            cn.aigc.dit.server.model.req.GenerateSubmitReq req = call.getArgument(0);
            refs.add(req.getReferences().get(0).getType() + ":" + req.getReferences().get(0).getStorageKey());
            GenerateTaskResp response = new GenerateTaskResp(); response.setId(22L); return response;
        });
        WorkflowRunReq req = new WorkflowRunReq(); req.setModelId(2L);
        req.setGraph(Map.of("nodes", List.of(
                Map.of("id", "audio", "data", Map.of("kind", "AudioGenerator", "media", Map.of("selectedAudioSource", "uploaded", "uploadedAudio", Map.of("storageKey", "users/7/input.wav")))),
                Map.of("id", "video", "data", Map.of("kind", "VideoGenerator", "media", Map.of("prompt", "dance")))),
                "edges", List.of(Map.of("source", "audio", "target", "video"))));
        var response = service.start(req);
        assertEquals(List.of("audio:users/7/input.wav"), refs);
        assertEquals("audio", response.getSteps().get(0).getOutputs().get(0).getType());
        assertEquals("users/7/input.wav", response.getSteps().get(0).getOutputs().get(0).getStorageKey());
        assertEquals(0L, response.getSteps().get(0).getCreditCost());
    }

    @Test
    void recoversSubmittedTaskAfterRunUpdateFailsAcrossServiceInstances() {
        WorkflowRun initial = run(step("video-b", TaskStatus.PENDING, null, List.of()));
        initial.getSteps().get(0).put("modelId", 2L);
        initial.getSteps().get(0).put("prompt", "Mountain");
        AtomicReference<WorkflowRun> durable = new AtomicReference<>(copyRun(initial));
        AtomicReference<GenTask> createdTask = new AtomicReference<>();
        AtomicBoolean failAfterSubmit = new AtomicBoolean(true);
        when(workflowRunMapper.selectById(1L)).thenAnswer(invocation -> copyRun(durable.get()));
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenAnswer(invocation -> {
            WorkflowRun candidate = invocation.getArgument(0);
            Map<String, Object> candidateStep = candidate.getSteps().get(0);
            if (TaskStatus.RUNNING.equals(candidateStep.get("status")) && failAfterSubmit.getAndSet(false)) {
                throw new IllegalStateException("simulated crash before workflow update");
            }
            durable.set(copyRun(candidate));
            return 1;
        });
        when(genTaskMapper.selectList(any())).thenAnswer(invocation ->
                createdTask.get() == null ? List.of() : List.of(createdTask.get()));
        when(generateService.submit(any(), any())).thenAnswer(invocation -> {
            assertEquals("SUBMITTING", durable.get().getSteps().get(0).get("status"));
            GenTask task = new GenTask();
            task.setId(22L);
            task.setStatus(TaskStatus.PENDING);
            task.setParams(Map.of("workflowRunId", 1L, "workflowNodeId", "video-b"));
            createdTask.set(task);
            GenerateTaskResp result = new GenerateTaskResp();
            result.setId(22L);
            return result;
        });

        assertThrows(IllegalStateException.class, () -> service.advance(1L));
        assertEquals("SUBMITTING", durable.get().getSteps().get(0).get("status"));

        WorkflowRunService secondInstance = new WorkflowRunService(
                workflowRunMapper, genTaskMapper, aiModelMapper, generateService, workflowService,
                workflowRunLock, filmComposer, creationService, objectStorage);
        secondInstance.advance(1L);

        assertEquals(TaskStatus.RUNNING, durable.get().getSteps().get(0).get("status"));
        assertEquals(22L, durable.get().getSteps().get(0).get("taskId"));
        verify(generateService, times(1)).submit(any(), any());
    }

    @Test
    void generatorRequiresMatchingNamedModelOrValidFallback() {
        WorkflowRunReq request = new WorkflowRunReq();
        request.setGraph(Map.of(
                "nodes", List.of(Map.of("id", "clip", "data", Map.of(
                        "kind", "VideoGenerator", "title", "Clip", "enabled", true,
                        "media", Map.of("prompt", "Mountain")))),
                "edges", List.of()));
        AiModel imageModel = new AiModel();
        imageModel.setId(3L);
        imageModel.setCategory("image");
        imageModel.setName("image-only");
        when(aiModelMapper.selectList(any())).thenReturn(List.of(imageModel));

        assertThrows(BizException.class, () -> service.start(request));
    }

    @Test
    void editComposesSelectedOutputsInConfiguredOrderAndRecordsFilm() {
        Map<String, Object> first = step("video-a", TaskStatus.SUCCEEDED, 11L, List.of());
        first.put("outputs", List.of(videoOutput("users/7/a-0.mp4", 5), videoOutput("users/7/a-1.mp4", 8)));
        Map<String, Object> second = step("video-b", TaskStatus.SUCCEEDED, 22L, List.of());
        second.put("outputs", List.of(videoOutput("users/7/b-0.mp4", 6), videoOutput("users/7/b-1.mp4", 7)));
        Map<String, Object> edit = editStep();
        edit.put("clips", List.of(
                clip("video-b", 0, 1, 0.0, null, true, 1.0),
                clip("video-a", 1, 2, 1.0, 4.0, false, 0.5)));
        WorkflowRun run = run(first, second, edit);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(filmComposer.compose(eq(7L), eq(1L), eq("edit"), eq(List.of(
                new WorkflowFilmComposer.Clip("users/7/b-0.mp4", 0, 6, true, 1),
                new WorkflowFilmComposer.Clip("users/7/a-1.mp4", 1, 4, false, 0.5))), isNull(), eq(List.of())))
                .thenReturn(new WorkflowFilmComposer.FilmOutput("users/7/film.mp4", "users/7/poster.jpg", 9));

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.SUCCEEDED, response.getStatus());
        WorkflowRunResp.StepResp result = response.getSteps().get(2);
        assertEquals(TaskStatus.SUCCEEDED, result.getStatus());
        assertEquals(0L, result.getCreditCost());
        assertEquals(100, result.getProgress());
        assertEquals(1, result.getOutputs().size());
        assertEquals("film", result.getOutputs().get(0).getType());
        assertEquals("/api/media?key=users%2F7%2Ffilm.mp4", result.getOutputs().get(0).getUrl());
        assertEquals("/api/media?key=users%2F7%2Fposter.jpg", result.getOutputs().get(0).getPosterUrl());
        assertEquals(9.0, result.getOutputs().get(0).getMetadata().get("durationSec"));
        verify(creationService).recordWorkflowFilm(7L, 1L, "edit", "edit",
                "users/7/film.mp4", "users/7/poster.jpg", 9.0);
        verify(generateService, never()).submit(any(), any());
    }

    @Test
    void persistedSuccessfulEditIsNotComposedAgain() {
        Map<String, Object> video = step("video-a", TaskStatus.SUCCEEDED, 11L, List.of());
        video.put("outputs", List.of(videoOutput("users/7/a.mp4", 5)));
        Map<String, Object> edit = editStep();
        edit.put("status", TaskStatus.SUCCEEDED);
        edit.put("progress", 100);
        edit.put("creditCost", 0L);
        edit.put("outputs", List.of(Map.of("type", "film", "storageKey", "users/7/film.mp4")));
        WorkflowRun run = run(video, edit);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.SUCCEEDED, response.getStatus());
        assertEquals("users/7/film.mp4", response.getSteps().get(1).getOutputs().get(0).getStorageKey());
        verify(filmComposer, never()).compose(any(), any(), any(), any());
        verify(creationService, never()).recordWorkflowFilm(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void editKeepsPlannerClipOrderWhenNewVideoWasAppendedAfterSparseOrders() {
        Map<String, Object> first = step("video-a", TaskStatus.SUCCEEDED, 11L, List.of());
        first.put("outputs", List.of(videoOutput("users/7/a.mp4", 5)));
        Map<String, Object> second = step("video-b", TaskStatus.SUCCEEDED, 22L, List.of());
        second.put("outputs", List.of(videoOutput("users/7/b.mp4", 5)));
        Map<String, Object> added = step("video-c", TaskStatus.SUCCEEDED, 33L, List.of());
        added.put("outputs", List.of(videoOutput("users/7/c.mp4", 5)));
        Map<String, Object> edit = editStep();
        edit.put("dependsOn", List.of("video-a", "video-b", "video-c"));
        edit.put("clips", List.of(
                clip("video-a", 0, 10, 0.0, 5.0, false, 1.0),
                clip("video-b", 0, 20, 0.0, 5.0, false, 1.0),
                clip("video-c", 0, 2, 0.0, 5.0, false, 1.0)));
        when(workflowRunMapper.selectById(1L)).thenReturn(run(first, second, added, edit));
        when(filmComposer.compose(eq(7L), eq(1L), eq("edit"), eq(List.of(
                new WorkflowFilmComposer.Clip("users/7/a.mp4", 0, 5, false, 1),
                new WorkflowFilmComposer.Clip("users/7/b.mp4", 0, 5, false, 1),
                new WorkflowFilmComposer.Clip("users/7/c.mp4", 0, 5, false, 1))), isNull(), eq(List.of())))
                .thenReturn(new WorkflowFilmComposer.FilmOutput("users/7/film.mp4", null, 15));

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.SUCCEEDED, response.getStatus());
        assertEquals("film", response.getSteps().get(3).getOutputs().get(0).getType());
    }

    @Test
    void editRecoversStoredFilmAfterCrashBeforeSuccessUpdate() throws Exception {
        Assumptions.assumeTrue(FfmpegSupport.available());
        Path fixture = directory.resolve("valid.mp4");
        Process ffmpeg = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=red:s=32x32:r=10", "-t", "0.4",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", fixture.toString()).start();
        assertTrue(ffmpeg.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, ffmpeg.exitValue());
        Map<String, Object> video = step("video-a", TaskStatus.SUCCEEDED, 11L, List.of());
        video.put("outputs", List.of(videoOutput("users/7/a.mp4", 0)));
        Map<String, Object> edit = editStep();
        edit.put("dependsOn", List.of("video-a"));
        edit.put("clips", List.of(clip("video-a", 0, 0, 0.0, 0.4, false, 1.0)));
        AtomicReference<WorkflowRun> durable = new AtomicReference<>(copyRun(run(video, edit)));
        AtomicBoolean failSuccessUpdate = new AtomicBoolean(true);
        when(workflowRunMapper.selectById(1L)).thenAnswer(invocation -> copyRun(durable.get()));
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenAnswer(invocation -> {
            WorkflowRun candidate = invocation.getArgument(0);
            if (TaskStatus.SUCCEEDED.equals(candidate.getSteps().get(1).get("status"))
                    && failSuccessUpdate.getAndSet(false)) {
                throw new IllegalStateException("crash after film upload");
            }
            durable.set(copyRun(candidate));
            return 1;
        });
        RememberingFilmComposer composer = new RememberingFilmComposer(
                new LocalObjectStorage(directory.resolve("objects")), Files.readAllBytes(fixture));
        WorkflowRunService first = new WorkflowRunService(workflowRunMapper, genTaskMapper, aiModelMapper,
                generateService, workflowService, workflowRunLock, composer, creationService, objectStorage);

        assertThrows(IllegalStateException.class, () -> first.advance(1L));
        assertEquals("EDITING", durable.get().getSteps().get(1).get("status"));

        WorkflowRunService second = new WorkflowRunService(workflowRunMapper, genTaskMapper, aiModelMapper,
                generateService, workflowService, workflowRunLock, composer, creationService, objectStorage);
        second.advance(1L);

        assertEquals(TaskStatus.SUCCEEDED, durable.get().getStatus());
        assertEquals("users/7/workflows/1/edit/film.mp4",
                ((Map<?, ?>) ((List<?>) durable.get().getSteps().get(1).get("outputs")).get(0)).get("storageKey"));
        assertEquals(1, composer.composeCalls);
        verify(creationService, times(2)).recordWorkflowFilm(7L, 1L, "edit", "edit",
                "users/7/workflows/1/edit/film.mp4", "users/7/workflows/1/edit/film-poster.webp", 0.4);
    }

    private static final class RememberingFilmComposer implements WorkflowFilmComposer {
        private final LocalObjectStorage storage;
        private final byte[] playableMp4;
        private final FfmpegWorkflowFilmComposer recovery;
        private int composeCalls;

        private RememberingFilmComposer(LocalObjectStorage storage, byte[] playableMp4) {
            this.storage = storage;
            this.playableMp4 = playableMp4;
            this.recovery = new FfmpegWorkflowFilmComposer(storage,
                    key -> "users/7/workflows/1/edit/film-poster.webp", new GenerateProperties());
        }

        @Override
        public FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target) {
            composeCalls++;
            String key = "users/7/workflows/1/edit/film.mp4";
            String poster = "users/7/workflows/1/edit/film-poster.webp";
            storage.put(key, playableMp4, "video/mp4");
            storage.put(poster, "poster".getBytes(), "image/webp");
            return target == null
                    ? new FilmOutput(key, poster, 0.4)
                    : new FilmOutput(key, poster, 0.4, target.width(), target.height());
        }

        @Override
        public FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips, FilmSize target) {
            return recovery.findExisting(userId, runId, nodeId, clips, target);
        }
    }

    @Test
    void composerFailureMarksOnlyEditFailedAndKeepsGeneratorOutputs() {
        Map<String, Object> video = step("video-a", TaskStatus.SUCCEEDED, 11L, List.of());
        video.put("outputs", List.of(videoOutput("users/7/a.mp4", 5)));
        Map<String, Object> edit = editStep();
        edit.put("dependsOn", List.of("video-a"));
        edit.put("clips", List.of(clip("video-a", 0, 0, 0.0, 5.0, false, 1.0)));
        WorkflowRun run = run(video, edit);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(filmComposer.compose(eq(7L), eq(1L), eq("edit"), eq(List.of(
                new WorkflowFilmComposer.Clip("users/7/a.mp4", 0, 5, false, 1))), isNull(), eq(List.of())))
                .thenThrow(new IllegalStateException("编码失败"));

        WorkflowRunResp response = service.getMine(1L);

        assertEquals("PARTIAL_FAILED", response.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, response.getSteps().get(0).getStatus());
        assertEquals("users/7/a.mp4", response.getSteps().get(0).getOutputs().get(0).getStorageKey());
        assertEquals(TaskStatus.FAILED, response.getSteps().get(1).getStatus());
        assertEquals("编码失败", response.getSteps().get(1).getErrorMessage());
        verify(generateService, never()).submit(any(), any());
    }

    @Test
    void missingSelectedVideoOutputFailsEditWithoutComposing() {
        Map<String, Object> video = step("video-a", TaskStatus.SUCCEEDED, 11L, List.of());
        video.put("outputs", List.of(videoOutput("users/7/a.mp4", 5)));
        Map<String, Object> edit = editStep();
        edit.put("dependsOn", List.of("video-a"));
        edit.put("clips", List.of(clip("video-a", 2, 0, 0.0, 5.0, false, 1.0)));
        WorkflowRun run = run(video, edit);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals("PARTIAL_FAILED", response.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, response.getSteps().get(0).getStatus());
        assertEquals(TaskStatus.FAILED, response.getSteps().get(1).getStatus());
        verify(filmComposer, never()).compose(any(), any(), any(), any());
    }

    @Test
    void malformedSelectedOutputIndexFailsEditWithoutComposing() {
        Map<String, Object> video = step("video-a", TaskStatus.SUCCEEDED, 11L, List.of());
        video.put("outputs", List.of(videoOutput("users/7/a.mp4", 5)));
        Map<String, Object> edit = editStep();
        edit.put("dependsOn", List.of("video-a"));
        Map<String, Object> selected = clip("video-a", 0, 0, 0.0, 5.0, false, 1.0);
        selected.put("outputIndex", "invalid");
        edit.put("clips", List.of(selected));
        when(workflowRunMapper.selectById(1L)).thenReturn(run(video, edit));

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.FAILED, response.getSteps().get(1).getStatus());
        verify(filmComposer, never()).compose(any(), any(), any(), any());
    }

    /** 内存版运行存储：insert/selectById/updateById/selectList 全部落到同一个列表 */
    private List<WorkflowRun> runStore() {
        List<WorkflowRun> stored = new ArrayList<>();
        when(workflowRunMapper.insert(any(WorkflowRun.class))).thenAnswer(invocation -> {
            WorkflowRun run = invocation.getArgument(0);
            run.setId((long) stored.size() + 1);
            stored.add(run);
            return 1;
        });
        when(workflowRunMapper.selectById(anyLong())).thenAnswer(invocation ->
                stored.get(((Long) invocation.getArgument(0)).intValue() - 1));
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenAnswer(invocation -> {
            WorkflowRun run = invocation.getArgument(0);
            stored.set((int) (run.getId() - 1), run);
            return 1;
        });
        // 历史查询只有带 workflowId 的运行才会用到，未保存画布走 previousRunId 链
        lenient().when(workflowRunMapper.selectList(any())).thenReturn(stored);
        return stored;
    }

    private static AiModel imageModel() {
        AiModel model = new AiModel();
        model.setId(9L);
        model.setCategory("image");
        model.setName("demo-image");
        model.setEnabled(true);
        return model;
    }

    private static GenerateTaskResp submittedTask() {
        GenerateTaskResp task = new GenerateTaskResp();
        task.setId(31L);
        task.setProgress(0);
        task.setCreditCost(12L);
        return task;
    }

    private void succeedTask(Long taskId) {
        GenTask task = new GenTask();
        task.setId(taskId);
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setCategory("image");
        task.setOutputKeys(List.of("users/7/outputs/1/1.png"));
        task.setProgress(100);
        task.setCreditCost(12L);
        when(genTaskMapper.selectById(taskId)).thenReturn(task);
    }

    private static Map<String, Object> imageGraph() {
        return Map.of(
                "nodes", List.of(Map.of("id", "image-a", "data", Map.of(
                        "kind", "ImageGenerator", "title", "Image A", "enabled", true,
                        "media", Map.of("prompt", "a cat", "model", "demo-image")))),
                "edges", List.of());
    }

    private static Map<String, Object> videoOutput(String key, int durationSec) {
        return Map.of("type", "video", "storageKey", key, "metadata", Map.of("durationSec", durationSec));
    }

    @Test
    void localImportedClipIsComposedDirectlyFromOwnedStorageKey() {
        Map<String, Object> local = clip("local:42", 0, 0, 0.5, 4.5, false, 0.8);
        local.put("localKey", "users/7/assets/local.mp4");
        Map<String, Object> edit = editStep();
        edit.put("dependsOn", List.of()); // 纯导入时间线，画布上没有上游视频生成步骤
        edit.put("clips", List.of(local));
        WorkflowRun run = run(edit);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);
        when(filmComposer.compose(eq(7L), eq(1L), eq("edit"), eq(List.of(
                new WorkflowFilmComposer.Clip("users/7/assets/local.mp4", 0.5, 4.5, false, 0.8))),
                isNull(), eq(List.of())))
                .thenReturn(new WorkflowFilmComposer.FilmOutput("users/7/film.mp4", "users/7/poster.jpg", 4));

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.SUCCEEDED, response.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, response.getSteps().get(0).getStatus());
        verify(creationService).recordWorkflowFilm(7L, 1L, "edit", "edit",
                "users/7/film.mp4", "users/7/poster.jpg", 4.0);
        verify(generateService, never()).submit(any(), any());
    }

    @Test
    void foreignLocalImportKeyIsRejectedBeforeComposing() {
        Map<String, Object> local = clip("local:42", 0, 0, 0.0, 3.0, false, 1.0);
        local.put("localKey", "users/99/assets/someone-else.mp4");
        Map<String, Object> edit = editStep();
        edit.put("dependsOn", List.of());
        edit.put("clips", List.of(local));
        WorkflowRun run = run(edit);
        when(workflowRunMapper.selectById(1L)).thenReturn(run);

        WorkflowRunResp response = service.getMine(1L);

        assertEquals(TaskStatus.FAILED, response.getSteps().get(0).getStatus());
        assertNotNull(response.getSteps().get(0).getErrorMessage());
        verify(filmComposer, never()).compose(any(), any(), any(), any(), any(), any());
    }

    private static Map<String, Object> clip(String source, int index, int order, double start,
                                            Double end, boolean muted, double volume) {
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("sourceNodeId", source);
        clip.put("outputIndex", index);
        clip.put("order", order);
        clip.put("trimStart", start);
        clip.put("trimEnd", end);
        clip.put("muted", muted);
        clip.put("volume", volume);
        return clip;
    }

    private static Map<String, Object> editStep() {
        Map<String, Object> step = step("edit", TaskStatus.PENDING, null, List.of("video-a", "video-b"));
        step.put("stepType", "EDIT");
        step.put("category", null);
        return step;
    }

    @SafeVarargs
    private static WorkflowRun run(Map<String, Object>... steps) {
        WorkflowRun run = new WorkflowRun();
        run.setId(1L);
        run.setUserId(7L);
        run.setStatus(TaskStatus.RUNNING);
        run.setTotal(steps.length);
        run.setProgress(0);
        run.setSteps(new ArrayList<>(List.of(steps)));
        return run;
    }

    private static Map<String, Object> step(String nodeId, String status, Long taskId, List<String> deps) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", nodeId);
        step.put("title", nodeId);
        step.put("stepType", "GENERATE");
        step.put("category", "video");
        step.put("status", status);
        step.put("taskId", taskId);
        step.put("dependsOn", deps);
        return step;
    }

    private static WorkflowRun copyRun(WorkflowRun source) {
        WorkflowRun copy = new WorkflowRun();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setStatus(source.getStatus());
        copy.setProgress(source.getProgress());
        copy.setTotal(source.getTotal());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> step : source.getSteps()) {
            steps.add(new LinkedHashMap<>(step));
        }
        copy.setSteps(steps);
        return copy;
    }
}
