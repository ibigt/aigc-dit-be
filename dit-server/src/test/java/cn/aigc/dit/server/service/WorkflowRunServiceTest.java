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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private final WorkflowRunLock workflowRunLock = runId -> () -> { };

    private WorkflowRunService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRunService(workflowRunMapper, genTaskMapper, aiModelMapper,
                generateService, workflowService, workflowRunLock, filmComposer, creationService);
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
    void textOnlyRunSucceedsWithoutModelOrGenerationTask() {
        WorkflowRunReq request = new WorkflowRunReq();
        request.setGraph(Map.of(
                "nodes", List.of(Map.of("id", "brief", "data", Map.of(
                        "kind", "MarkdownNote", "title", "Brief", "enabled", true,
                        "fields", List.of(Map.of("label", "内容", "value", "Rainy night"))))),
                "edges", List.of()));
        when(aiModelMapper.selectList(any())).thenReturn(List.of());
        final WorkflowRun[] stored = new WorkflowRun[1];
        when(workflowRunMapper.selectById(1L)).thenAnswer(invocation -> stored[0]);
        when(workflowRunMapper.updateById(any(WorkflowRun.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return 1;
        });
        when(workflowRunMapper.insert(any(WorkflowRun.class))).thenAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            stored[0].setId(1L);
            return 1;
        });

        WorkflowRunResp response = service.start(request);

        assertEquals(TaskStatus.SUCCEEDED, response.getStatus());
        assertEquals("TEXT", response.getSteps().get(0).getStepType());
        assertEquals("text", response.getSteps().get(0).getOutputs().get(0).getType());
        assertEquals("Rainy night", response.getSteps().get(0).getOutputs().get(0).getValue());
        assertNull(response.getSteps().get(0).getTaskId());
        assertEquals(0L, response.getSteps().get(0).getCreditCost());
        verify(generateService, never()).submit(any(), any());
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
                workflowRunLock, filmComposer, creationService);
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
        when(filmComposer.compose(7L, 1L, "edit", List.of(
                new WorkflowFilmComposer.Clip("users/7/b-0.mp4", 0, 6, true, 1),
                new WorkflowFilmComposer.Clip("users/7/a-1.mp4", 1, 4, false, 0.5))))
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
        when(filmComposer.compose(7L, 1L, "edit", List.of(
                new WorkflowFilmComposer.Clip("users/7/a.mp4", 0, 5, false, 1),
                new WorkflowFilmComposer.Clip("users/7/b.mp4", 0, 5, false, 1),
                new WorkflowFilmComposer.Clip("users/7/c.mp4", 0, 5, false, 1))))
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
                generateService, workflowService, workflowRunLock, composer, creationService);

        assertThrows(IllegalStateException.class, () -> first.advance(1L));
        assertEquals("EDITING", durable.get().getSteps().get(1).get("status"));

        WorkflowRunService second = new WorkflowRunService(workflowRunMapper, genTaskMapper, aiModelMapper,
                generateService, workflowService, workflowRunLock, composer, creationService);
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
        public FilmOutput compose(Long userId, Long runId, String nodeId, List<Clip> clips) {
            composeCalls++;
            String key = "users/7/workflows/1/edit/film.mp4";
            String poster = "users/7/workflows/1/edit/film-poster.webp";
            storage.put(key, playableMp4, "video/mp4");
            storage.put(poster, "poster".getBytes(), "image/webp");
            return new FilmOutput(key, poster, 0.4);
        }

        @Override
        public FilmOutput findExisting(Long userId, Long runId, String nodeId, List<Clip> clips) {
            return recovery.findExisting(userId, runId, nodeId, clips);
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
        when(filmComposer.compose(7L, 1L, "edit", List.of(
                new WorkflowFilmComposer.Clip("users/7/a.mp4", 0, 5, false, 1))))
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

    private static Map<String, Object> videoOutput(String key, int durationSec) {
        return Map.of("type", "video", "storageKey", key, "metadata", Map.of("durationSec", durationSec));
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
