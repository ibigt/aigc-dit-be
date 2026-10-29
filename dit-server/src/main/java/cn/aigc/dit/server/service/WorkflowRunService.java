package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.entity.WorkflowRun;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.WorkflowRunMapper;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.req.WorkflowRunReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.model.resp.WorkflowRunResp;
import cn.aigc.dit.server.model.resp.WorkflowSnapshotResp;
import cn.aigc.dit.server.workflow.WorkflowGraphPlanner;
import cn.aigc.dit.server.workflow.WorkflowGraphPlanner.Step;
import cn.aigc.dit.server.workflow.WorkflowFilmComposer;
import cn.aigc.dit.server.workflow.WorkflowStepFingerprint;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRunService {

    private static final int MAX_REUSE_RUNS = 50;

    private final WorkflowRunMapper workflowRunMapper;
    private final GenTaskMapper genTaskMapper;
    private final AiModelMapper aiModelMapper;
    private final GenerateService generateService;
    private final WorkflowService workflowService;
    private final WorkflowRunLock workflowRunLock;
    private final WorkflowFilmComposer filmComposer;
    private final CreationService creationService;

    public WorkflowRunResp start(WorkflowRunReq req) {
        if (req.getWorkflowId() != null) {
            workflowService.requireReadable(req.getWorkflowId());
        }
        List<Step> plan = WorkflowGraphPlanner.plan(req.getGraph(), req.getNodeId());
        List<AiModel> models = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>().eq(AiModel::getEnabled, true));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step step : plan) {
            if ("ASSET".equals(step.stepType()) && step.refs().stream().anyMatch(ref -> !ownedKey(ref.storageKey(), UserContext.requireUserId()))) {
                throw new BizException("节点「" + step.nodeId() + "」参考音频不属于当前用户");
            }
            steps.add(toStep(step, resolveModel(step, req.getModelId(), models)));
        }
        WorkflowRun prior = null;
        if (req.getPreviousRunId() != null) {
            prior = requireOwned(req.getPreviousRunId());
            if (!java.util.Objects.equals(req.getWorkflowId(), prior.getWorkflowId())) {
                throw new BizException("前次运行不属于当前工作流");
            }
        }
        List<WorkflowRun> history = req.getWorkflowId() == null ? ownedHistoryChain(prior)
                : req.getNodeId() == null ? List.of() : ownedHistory(req.getWorkflowId(), MAX_REUSE_RUNS);
        initializeReusable(steps, history, req.getNodeId());
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(req.getWorkflowId());
        run.setPreviousRunId(prior == null ? null : prior.getId());
        run.setUserId(UserContext.requireUserId());
        run.setModelId(req.getModelId());
        run.setStatus(TaskStatus.RUNNING);
        run.setProgress(0);
        run.setTotal(steps.size());
        run.setSteps(steps);
        workflowRunMapper.insert(run);
        advance(run.getId());
        return toResp(requireOwned(run.getId()));
    }

    public WorkflowRunResp getMine(Long id) {
        WorkflowRun run = requireOwned(id);
        if (TaskStatus.RUNNING.equals(run.getStatus())) {
            advance(id);
            run = requireOwned(id);
        }
        return toResp(run);
    }

    public WorkflowRunResp latestMine(Long workflowId) {
        workflowService.requireReadable(workflowId);
        WorkflowRun run = latestOwned(workflowId);
        return run == null ? null : getMine(run.getId());
    }

    public WorkflowSnapshotResp snapshotMine(Long workflowId) {
        var workflow = workflowService.requireReadable(workflowId);
        List<WorkflowRun> history = ownedHistory(workflowId, MAX_REUSE_RUNS);
        List<WorkflowSnapshotResp.RunState> active = history.stream()
                .filter(run -> TaskStatus.RUNNING.equals(run.getStatus()))
                .map(run -> new WorkflowSnapshotResp.RunState(run.getId(), run.getStatus())).toList();
        if (history.isEmpty()) return new WorkflowSnapshotResp(workflowId, null, List.of(), active);
        List<AiModel> models = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>());
        Map<String, List<Step>> branches = WorkflowGraphPlanner.recoveryBranches(workflow.getGraph());
        Map<String, Map<String, Object>> validSteps = new LinkedHashMap<>();
        for (List<Step> branch : branches.values()) {
            try {
                // Convert atomically so an unavailable model invalidates only its dependent branch.
                List<Map<String, Object>> planned = branch.stream()
                        .map(step -> toStep(step, resolveModel(step, workflow.getModelId(), models))).toList();
                planned.forEach(step -> validSteps.putIfAbsent(text(step.get("nodeId")), step));
            } catch (BizException invalidBranch) {
                // An unfinished node must not prevent recovery of unrelated matching history.
            }
        }
        List<Map<String, Object>> steps = new ArrayList<>(validSteps.values());
        Map<String, Long> sourceRuns = new LinkedHashMap<>();
        for (Map<String, Object> step : steps) {
            String nodeId = text(step.get("nodeId"));
            String fingerprint = WorkflowStepFingerprint.of(step, steps);
            step.put("inputFingerprint", fingerprint);
            step.put("status", "IDLE");
            for (WorkflowRun run : history) {
                Map<String, Object> previous = copySteps(run.getSteps()).stream()
                        .filter(candidate -> nodeId.equals(text(candidate.get("nodeId")))).findFirst().orElse(null);
                if (previous == null) continue;
                boolean matches = fingerprint.equals(previous.get("inputFingerprint"));
                if (!matches && previous.get("inputFingerprint") == null
                        && ("PENDING".equals(previous.get("status")) || "BLOCKED".equals(previous.get("status")))) {
                    matches = pendingPlanMatches(nodeId, steps, copySteps(run.getSteps()), new java.util.HashSet<>());
                }
                if (!matches || (TaskStatus.SUCCEEDED.equals(previous.get("status")) && !durableOutputs(previous))) continue;
                for (String field : List.of("status", "progress", "creditCost", "taskId", "error", "outputs", "outputStorageKey", "posterKey")) {
                    if (previous.containsKey(field)) step.put(field, previous.get(field));
                }
                sourceRuns.put(nodeId, run.getId());
                break;
            }
        }
        for (String nodeId : branches.keySet()) {
            if (!validSteps.containsKey(nodeId)) steps.add(Map.of("nodeId", nodeId, "status", "IDLE"));
        }
        List<WorkflowRunResp.StepResp> responses = stepResponses(steps, null);
        responses.forEach(step -> {
            step.setRunId(sourceRuns.get(step.getNodeId()));
            step.setOutputs("IDLE".equals(step.getStatus()) ? List.of() : step.getOutputs().stream()
                    .filter(output -> "text".equals(output.getType()) || ownedKey(output.getStorageKey(), UserContext.requireUserId())).toList());
        });
        return new WorkflowSnapshotResp(workflowId, history.get(0).getId(), responses, active);
    }

    private static boolean durableOutputs(Map<String, Object> step) {
        if (!(step.get("outputs") instanceof List<?> outputs) || outputs.isEmpty()) return false;
        if ("TEXT".equals(step.get("stepType"))) return true;
        return outputs.stream().allMatch(output -> output instanceof Map<?, ?> map
                && ownedKey(text(map.get("storageKey")), UserContext.requireUserId()));
    }

    /** A queued step has no runtime fingerprint yet; verify its complete current input plan instead. */
    private static boolean pendingPlanMatches(String nodeId, List<Map<String, Object>> planned,
                                              List<Map<String, Object>> previous, java.util.Set<String> visited) {
        if (!visited.add(nodeId)) return true;
        Map<String, Object> current = planned.stream().filter(step -> nodeId.equals(step.get("nodeId"))).findFirst().orElse(null);
        Map<String, Object> old = previous.stream().filter(step -> nodeId.equals(step.get("nodeId"))).findFirst().orElse(null);
        if (current == null || old == null || !java.util.Objects.equals(intValue(current.get("outputIndex")), intValue(old.get("outputIndex")))
                || !strings(current.get("dependsOn")).equals(strings(old.get("dependsOn")))
                || !WorkflowStepFingerprint.of(current, planned).equals(WorkflowStepFingerprint.of(old, planned))) return false;
        return strings(current.get("dependsOn")).stream().allMatch(id -> pendingPlanMatches(id, planned, previous, visited));
    }

    @Scheduled(fixedDelay = 4000, initialDelay = 8000)
    public void tick() {
        List<WorkflowRun> runs = workflowRunMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getStatus, TaskStatus.RUNNING)
                .orderByAsc(WorkflowRun::getId)
                .last("LIMIT 20"));
        for (WorkflowRun run : runs) {
            try {
                advance(run.getId());
            } catch (Exception ex) {
                log.warn("workflow run {} advance failed: {}", run.getId(), ex.getMessage());
            }
        }
    }

    public void advance(Long runId) {
        try (WorkflowRunLock.Handle lock = workflowRunLock.tryAcquire(runId)) {
            if (lock != null) {
                doAdvance(runId);
            }
        }
    }

    private boolean doAdvance(Long runId) {
        WorkflowRun run = workflowRunMapper.selectById(runId);
        if (run == null || !TaskStatus.RUNNING.equals(run.getStatus())) {
            return true;
        }
        List<Map<String, Object>> steps = copySteps(run.getSteps());
        boolean changed = refreshTasks(steps);
        changed |= markBlockedDependents(steps);
        for (Map<String, Object> step : steps) {
            String status = text(step.get("status"));
            if (!"PENDING".equals(status) && !"SUBMITTING".equals(status) && !"EDITING".equals(status)) {
                continue;
            }
            if ("PENDING".equals(status) && !depsSucceeded(step, steps)) {
                continue;
            }
            if (step.get("inputFingerprint") == null) {
                step.put("inputFingerprint", WorkflowStepFingerprint.of(step, steps));
            }
            if (longValue(step.get("taskId")) != null) {
                step.put("status", TaskStatus.RUNNING);
                changed = true;
                continue;
            }
            if ("TEXT".equals(text(step.get("stepType"))) || "ASSET".equals(text(step.get("stepType")))) {
                step.put("status", TaskStatus.SUCCEEDED);
                step.put("progress", 100);
                step.put("creditCost", 0L);
                changed = true;
                continue;
            }
            if ("EDIT".equals(text(step.get("stepType")))) {
                if (!"EDITING".equals(status)) {
                    step.put("status", "EDITING");
                    run.setSteps(steps);
                    run.setProgress(count(steps, TaskStatus.SUCCEEDED));
                    workflowRunMapper.updateById(run);
                }
                try {
                    List<WorkflowFilmComposer.Clip> clips = editClips(step, steps);
                    String nodeId = text(step.get("nodeId"));
                    WorkflowFilmComposer.FilmOutput film = filmComposer.findExisting(run.getUserId(), run.getId(),
                            nodeId, clips);
                    if (film == null) {
                        film = filmComposer.compose(run.getUserId(), run.getId(), nodeId, clips);
                    }
                    creationService.recordWorkflowFilm(run.getUserId(), run.getId(), text(step.get("nodeId")),
                            text(step.get("title")), film.storageKey(), film.posterKey(), film.durationSeconds());
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "film");
                    output.put("storageKey", film.storageKey());
                    output.put("posterKey", film.posterKey());
                    output.put("metadata", Map.of("durationSec", film.durationSeconds()));
                    step.put("outputs", List.of(output));
                    step.put("outputStorageKey", film.storageKey());
                    step.put("status", TaskStatus.SUCCEEDED);
                    step.put("progress", 100);
                    step.put("creditCost", 0L);
                    step.put("error", null);
                } catch (RuntimeException ex) {
                    step.put("status", TaskStatus.FAILED);
                    step.put("error", clip(ex.getMessage() == null ? "视频剪辑失败" : ex.getMessage()));
                }
                changed = true;
                continue;
            }
            step.put("status", "SUBMITTING");
            run.setSteps(steps);
            run.setProgress(count(steps, TaskStatus.SUCCEEDED));
            workflowRunMapper.updateById(run);
            GenTask recovered = findWorkflowTask(run.getId(), run.getUserId(), text(step.get("nodeId")));
            if (recovered != null) {
                step.put("status", TaskStatus.RUNNING);
                step.put("taskId", recovered.getId());
                step.put("progress", recovered.getProgress());
                step.put("creditCost", recovered.getCreditCost());
                changed = true;
                continue;
            }
            try {
                GenerateTaskResp task = submitAs(run.getUserId(), requestOf(step, steps), run.getId(), text(step.get("nodeId")));
                if (task == null || task.getId() == null) {
                    throw new IllegalStateException("生成任务未返回 ID");
                }
                step.put("status", TaskStatus.RUNNING);
                step.put("taskId", task.getId());
                step.put("progress", task.getProgress());
                step.put("creditCost", task.getCreditCost());
            } catch (RuntimeException ex) {
                recovered = findWorkflowTask(run.getId(), run.getUserId(), text(step.get("nodeId")));
                if (recovered != null) {
                    step.put("status", TaskStatus.RUNNING);
                    step.put("taskId", recovered.getId());
                    step.put("progress", recovered.getProgress());
                    step.put("creditCost", recovered.getCreditCost());
                } else {
                    step.put("status", TaskStatus.FAILED);
                    step.put("error", clip(ex.getMessage() == null ? "生成失败" : ex.getMessage()));
                }
            }
            changed = true;
        }
        changed |= markBlockedDependents(steps);
        if (allTerminal(steps)) {
            boolean partial = steps.stream().anyMatch(step -> TaskStatus.FAILED.equals(text(step.get("status")))
                    || "BLOCKED".equals(text(step.get("status"))));
            finish(run, steps, partial ? "PARTIAL_FAILED" : TaskStatus.SUCCEEDED,
                    partial ? aggregateErrors(steps) : null);
            return true;
        }
        if (changed) {
            run.setSteps(steps);
            run.setProgress(count(steps, TaskStatus.SUCCEEDED));
            workflowRunMapper.updateById(run);
        }
        return false;
    }

    private GenTask findWorkflowTask(Long runId, Long userId, String nodeId) {
        List<GenTask> tasks = genTaskMapper.selectList(new QueryWrapper<GenTask>()
                .eq("user_id", userId)
                .apply("params->>'workflowRunId' = {0}", String.valueOf(runId))
                .apply("params->>'workflowNodeId' = {0}", nodeId)
                .orderByAsc("id")
                .last("LIMIT 1"));
        return tasks == null || tasks.isEmpty() ? null : tasks.get(0);
    }

    private boolean refreshTasks(List<Map<String, Object>> steps) {
        boolean changed = false;
        for (Map<String, Object> step : steps) {
            if (!TaskStatus.RUNNING.equals(text(step.get("status")))) {
                continue;
            }
            Long taskId = longValue(step.get("taskId"));
            if (taskId == null) {
                step.put("status", TaskStatus.FAILED);
                step.put("error", "生成任务丢失");
                changed = true;
                continue;
            }
            GenTask task = genTaskMapper.selectById(taskId);
            if (task == null) {
                step.put("status", TaskStatus.FAILED);
                step.put("error", "生成任务丢失");
                changed = true;
                continue;
            }
            if (!java.util.Objects.equals(step.get("creditCost"), task.getCreditCost())) {
                step.put("creditCost", task.getCreditCost());
                changed = true;
            }
            if (TaskStatus.SUCCEEDED.equals(task.getStatus())) {
                step.put("status", TaskStatus.SUCCEEDED);
                step.put("outputStorageKey", task.getOutputStorageKey());
                List<String> keys = task.getOutputKeys() == null || task.getOutputKeys().isEmpty()
                        ? task.getOutputStorageKey() == null ? List.of() : List.of(task.getOutputStorageKey())
                        : task.getOutputKeys();
                List<Map<String, Object>> outputs = new ArrayList<>();
                Map<String, Object> metadata = new LinkedHashMap<>();
                if (task.getParams() != null) {
                    for (String field : List.of("ratio", "resolution", "durationSec", "copies")) {
                        Object value = task.getParams().get(field);
                        if (value != null) {
                            metadata.put(field, value);
                        }
                    }
                    if (task.getParams().get("metadata") instanceof Map<?, ?> additions) {
                        additions.forEach((key, value) -> metadata.put(String.valueOf(key), value));
                    }
                }
                for (String key : keys) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", text(step.get("category")));
                    output.put("storageKey", key);
                    output.put("posterKey", task.getPosterKey());
                    if (!metadata.isEmpty()) {
                        output.put("metadata", metadata);
                    }
                    outputs.add(output);
                }
                step.put("outputs", outputs);
                step.put("creditCost", task.getCreditCost());
                step.put("progress", task.getProgress());
                step.put("error", null);
                changed = true;
            } else if (TaskStatus.FAILED.equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
                step.put("status", TaskStatus.FAILED);
                step.put("error", clip(task.getErrorMessage() == null ? "生成失败" : task.getErrorMessage()));
                step.put("progress", task.getProgress());
                changed = true;
            } else if (task.getProgress() != null && !task.getProgress().equals(intValue(step.get("progress")))) {
                step.put("progress", task.getProgress());
                changed = true;
            }
        }
        return changed;
    }

    private GenerateSubmitReq requestOf(Map<String, Object> step, List<Map<String, Object>> steps) {
        GenerateSubmitReq req = new GenerateSubmitReq();
        req.setModelId(longValue(step.get("modelId")));
        req.setCategory(text(step.get("category")));
        req.setPrompt(text(step.get("resolvedPrompt")) == null
                ? text(step.get("prompt")) : text(step.get("resolvedPrompt")));
        req.setRatio(text(step.get("ratio")));
        req.setResolution(text(step.get("resolution")));
        req.setQuality(text(step.get("quality")));
        req.setDurationSec(intValue(step.get("durationSec")));
        req.setCopies(intValue(step.get("copies")));
        req.setSpeed(doubleValue(step.get("speed")));
        req.setVoiceId(text(step.get("voiceId")));
        req.setAudioMode(text(step.get("audioMode")));
        req.setEmotion(text(step.get("emotion")));
        req.setLyrics(text(step.get("lyrics")));
        req.setInstrumental(step.get("instrumental") instanceof Boolean flag ? flag : null);
        req.setMusicStyle(text(step.get("musicStyle")));
        req.setMusicDuration(step.get("musicDuration") instanceof Number number ? number.doubleValue() : null);
        String category = text(step.get("category"));
        if (!ModelCategory.AUDIO.equals(category)) {
            List<GenerateSubmitReq.ReferenceInput> refs = collectRefs(step, steps, category);
            if (Boolean.TRUE.equals(step.get("frameMode"))) {
                List<GenerateSubmitReq.ReferenceInput> images = refs.stream()
                        .filter(ref -> ModelCategory.IMAGE.equals(ref.getType()))
                        .toList();
                if (images.size() >= 2) {
                    GenerateSubmitReq.FrameInput frames = new GenerateSubmitReq.FrameInput();
                    frames.setFirstFrameKey(images.get(0).getStorageKey());
                    frames.setLastFrameKey(images.get(1).getStorageKey());
                    req.setFrames(frames);
                    refs = refs.stream().filter(ref -> !ModelCategory.IMAGE.equals(ref.getType())).toList();
                }
            }
            if (!refs.isEmpty()) {
                req.setReferences(refs);
            }
        }
        return req;
    }

    private List<GenerateSubmitReq.ReferenceInput> collectRefs(Map<String, Object> step, List<Map<String, Object>> steps, String category) {
        List<GenerateSubmitReq.ReferenceInput> refs = new ArrayList<>();
        if (step.get("staticRefs") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    addRef(refs, text(map.get("type")), text(map.get("storageKey")), category);
                }
            }
        }
        for (String depId : strings(step.get("dependsOn"))) {
            Map<String, Object> dep = steps.stream().filter(item -> depId.equals(text(item.get("nodeId")))).findFirst().orElse(null);
            if (dep == null) {
                continue;
            }
            Map<?, ?> output = selectedOutput(dep);
            addRef(refs, output == null ? text(dep.get("category")) : text(output.get("type")),
                    output == null ? text(dep.get("outputStorageKey")) : text(output.get("storageKey")), category);
        }
        return refs.size() > 15 ? refs.subList(0, 15) : refs;
    }

    private static Map<?, ?> selectedOutput(Map<String, Object> step) {
        if (!(step.get("outputs") instanceof List<?> outputs) || outputs.isEmpty()) return null;
        Integer selected = intValue(step.get("outputIndex"));
        int index = selected != null && selected >= 0 && selected < outputs.size() ? selected : 0;
        return outputs.get(index) instanceof Map<?, ?> output ? output : null;
    }

    private static void addRef(List<GenerateSubmitReq.ReferenceInput> refs, String type, String storageKey, String category) {
        if (!WorkflowGraphPlanner.storageKey(storageKey) || type == null) {
            return;
        }
        String normalized = type.toLowerCase(java.util.Locale.ROOT);
        if (!List.of(ModelCategory.VIDEO, ModelCategory.IMAGE, ModelCategory.AUDIO).contains(normalized)) return;
        if (ModelCategory.IMAGE.equals(category) && !ModelCategory.IMAGE.equals(normalized)) {
            return;
        }
        GenerateSubmitReq.ReferenceInput input = new GenerateSubmitReq.ReferenceInput();
        input.setType(normalized);
        input.setStorageKey(storageKey);
        refs.add(input);
    }

    private GenerateTaskResp submitAs(Long userId, GenerateSubmitReq req, Long runId, String nodeId) {
        LoginUser previous = UserContext.get();
        UserContext.set(new LoginUser(userId, null, null, null, LoginUser.ROLE_CREATOR));
        try {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("workflowRunId", runId);
            extra.put("workflowNodeId", nodeId);
            return generateService.submit(req, extra);
        } finally {
            if (previous == null) {
                UserContext.clear();
            } else {
                UserContext.set(previous);
            }
        }
    }

    private static List<WorkflowFilmComposer.Clip> editClips(Map<String, Object> step,
                                                               List<Map<String, Object>> steps) {
        if (!(step.get("clips") instanceof List<?> raw) || raw.isEmpty()) {
            throw new IllegalArgumentException("请选择要合成的视频片段");
        }
        List<Map<?, ?>> configured = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> clip)) {
                throw new IllegalArgumentException("视频片段配置无效");
            }
            configured.add(clip);
        }
        List<WorkflowFilmComposer.Clip> clips = new ArrayList<>();
        for (Map<?, ?> configuredClip : configured) {
            String sourceId = text(configuredClip.get("sourceNodeId"));
            Map<String, Object> source = steps.stream()
                    .filter(item -> sourceId != null && sourceId.equals(text(item.get("nodeId"))))
                    .findFirst().orElse(null);
            if (source == null || !TaskStatus.SUCCEEDED.equals(text(source.get("status")))) {
                throw new IllegalArgumentException("源视频节点未成功：" + sourceId);
            }
            Integer selectedIndex = configuredClip.get("outputIndex") == null
                    ? 0 : intValue(configuredClip.get("outputIndex"));
            if (selectedIndex == null) {
                throw new IllegalArgumentException("源视频输出索引无效：" + sourceId);
            }
            int index = selectedIndex;
            if (!(source.get("outputs") instanceof List<?> outputs) || index < 0 || index >= outputs.size()
                    || !(outputs.get(index) instanceof Map<?, ?> output)
                    || !ModelCategory.VIDEO.equals(text(output.get("type")))
                    || !WorkflowGraphPlanner.storageKey(text(output.get("storageKey")))) {
                throw new IllegalArgumentException("源视频输出不存在：" + sourceId + " #" + index);
            }
            double start = doubleValue(configuredClip.get("trimStart")) == null
                    ? 0 : doubleValue(configuredClip.get("trimStart"));
            Double end = doubleValue(configuredClip.get("trimEnd"));
            if (end == null && output.get("metadata") instanceof Map<?, ?> metadata) {
                end = doubleValue(metadata.get("durationSec"));
                if (end == null) {
                    Double durationMs = doubleValue(metadata.get("durationMs"));
                    end = durationMs == null ? null : durationMs / 1000;
                }
            }
            if (end == null) {
                throw new IllegalArgumentException("源视频时长未知：" + sourceId + " #" + index);
            }
            Double volume = doubleValue(configuredClip.get("volume"));
            clips.add(new WorkflowFilmComposer.Clip(text(output.get("storageKey")), start, end,
                    Boolean.TRUE.equals(configuredClip.get("muted")), volume == null ? 1 : volume));
        }
        return List.copyOf(clips);
    }

    private Long resolveModel(Step step, Long fallbackId, List<AiModel> models) {
        if (!"GENERATE".equals(step.stepType())) {
            return null;
        }
        if (step.modelName() != null) {
            for (AiModel model : models) {
                if (step.category().equalsIgnoreCase(model.getCategory()) && step.modelName().equals(model.getName())) {
                    return model.getId();
                }
            }
        }
        AiModel fallback = models.stream().filter(model -> model.getId().equals(fallbackId)).findFirst().orElse(null);
        if (fallback != null && step.category().equalsIgnoreCase(fallback.getCategory())) {
            return fallback.getId();
        }
        throw new BizException("没有可用的" + categoryLabel(step.category()) + "模型，节点「" + step.title() + "」无法运行");
    }

    private Map<String, Object> toStep(Step step, Long modelId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", step.nodeId());
        map.put("title", step.title());
        map.put("kind", step.kind());
        map.put("category", step.category());
        map.put("stepType", step.stepType());
        map.put("prompt", step.prompt());
        map.put("resolvedPrompt", step.resolvedPrompt());
        map.put("ratio", step.ratio());
        map.put("resolution", step.resolution());
        map.put("quality", step.quality());
        map.put("durationSec", step.durationSec());
        map.put("copies", step.copies());
        map.put("speed", step.speed());
        map.putAll(step.audio());
        map.put("modelId", modelId);
        map.put("frameMode", step.frameMode());
        map.put("outputIndex", step.outputIndex());
        map.put("dependsOn", step.dependsOn());
        map.put("clips", step.clips().stream().map(clip -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceNodeId", clip.sourceNodeId());
            item.put("outputIndex", clip.outputIndex());
            item.put("order", clip.order());
            item.put("trimStart", clip.trimStart());
            item.put("trimEnd", clip.trimEnd());
            item.put("muted", clip.muted());
            item.put("volume", clip.volume());
            return item;
        }).toList());
        map.put("staticRefs", step.refs().stream().map(ref -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("type", ref.type());
            item.put("storageKey", ref.storageKey());
            return item;
        }).toList());
        if ("TEXT".equals(step.stepType())) {
            map.put("outputs", step.outputs().stream().map(output -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "text");
                item.put("name", output.name());
                item.put("value", output.value());
                return item;
            }).toList());
        } else if ("ASSET".equals(step.stepType())) {
            map.put("outputs", step.refs().stream().map(ref -> Map.of("type", "audio", "storageKey", ref.storageKey())).toList());
            map.put("outputIndex", 0);
        }
        map.put("status", TaskStatus.PENDING);
        return map;
    }

    private static boolean ownedKey(String key, Long userId) {
        return key != null && key.startsWith("users/" + userId + "/") && !key.contains("..") && !key.contains("\\");
    }

    private WorkflowRun latestOwned(Long workflowId) {
        List<WorkflowRun> candidates = ownedHistory(workflowId, 1);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private List<WorkflowRun> ownedHistory(Long workflowId, int limit) {
        List<WorkflowRun> candidates = workflowRunMapper.selectList(new QueryWrapper<WorkflowRun>()
                .eq("user_id", UserContext.requireUserId()).eq("workflow_id", workflowId)
                .orderByDesc("id").last("LIMIT " + limit));
        return candidates == null ? List.of() : candidates;
    }

    private List<WorkflowRun> ownedHistoryChain(WorkflowRun latest) {
        List<WorkflowRun> history = new ArrayList<>();
        java.util.Set<Long> visited = new java.util.HashSet<>();
        WorkflowRun current = latest;
        while (current != null && history.size() < MAX_REUSE_RUNS && visited.add(current.getId())) {
            if (!java.util.Objects.equals(current.getWorkflowId(), latest.getWorkflowId())) {
                throw new BizException("前次运行链不属于当前工作流");
            }
            history.add(current);
            current = current.getPreviousRunId() == null ? null : requireOwned(current.getPreviousRunId());
        }
        return history;
    }

    private static void initializeReusable(List<Map<String, Object>> steps, List<WorkflowRun> history, String target) {
        Long userId = UserContext.requireUserId();
        for (Map<String, Object> step : steps) {
            if (!depsSucceeded(step, steps)) continue;
            String fingerprint = WorkflowStepFingerprint.of(step, steps);
            step.put("inputFingerprint", fingerprint);
            if (List.of("TEXT", "ASSET").contains(text(step.get("stepType")))) {
                step.put("status", TaskStatus.SUCCEEDED);
                step.put("progress", 100);
                step.put("creditCost", 0L);
                continue;
            }
            if (target == null || target.equals(text(step.get("nodeId")))) continue;
            for (WorkflowRun prior : history) {
                if (!userId.equals(prior.getUserId())) continue;
                Map<String, Object> previous = copySteps(prior.getSteps()).stream()
                        .filter(candidate -> java.util.Objects.equals(step.get("nodeId"), candidate.get("nodeId"))
                                && TaskStatus.SUCCEEDED.equals(candidate.get("status"))
                                && fingerprint.equals(candidate.get("inputFingerprint")))
                        .findFirst().orElse(null);
                if (previous == null || !(previous.get("outputs") instanceof List<?> outputs) || outputs.isEmpty()
                        || outputs.stream().anyMatch(output -> !(output instanceof Map<?, ?> map)
                        || !ownedKey(text(map.get("storageKey")), userId))) continue;
                step.put("outputs", previous.get("outputs"));
                step.put("status", TaskStatus.SUCCEEDED);
                step.put("progress", 100);
                step.put("creditCost", 0L);
                step.put("reusedFromRunId", prior.getId());
                break;
            }
        }
    }

    private void finish(WorkflowRun run, List<Map<String, Object>> steps, String status, String error) {
        run.setSteps(steps);
        run.setStatus(status);
        run.setProgress(count(steps, TaskStatus.SUCCEEDED));
        run.setErrorMessage(error);
        run.setFinishedAt(OffsetDateTime.now());
        workflowRunMapper.updateById(run);
    }

    private WorkflowRun requireOwned(Long id) {
        WorkflowRun run = workflowRunMapper.selectById(id);
        if (run == null || !UserContext.requireUserId().equals(run.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行记录不存在");
        }
        return run;
    }

    private WorkflowRunResp toResp(WorkflowRun run) {
        WorkflowRunResp resp = new WorkflowRunResp();
        resp.setId(run.getId());
        resp.setWorkflowId(run.getWorkflowId());
        resp.setStatus(run.getStatus());
        resp.setProgress(run.getProgress());
        resp.setTotal(run.getTotal());
        resp.setErrorMessage(run.getErrorMessage());
        resp.setCreatedAt(run.getCreatedAt());
        resp.setFinishedAt(run.getFinishedAt());
        resp.setSteps(stepResponses(run.getSteps(), run.getId()));
        return resp;
    }

    private List<WorkflowRunResp.StepResp> stepResponses(List<Map<String, Object>> storedSteps, Long runId) {
        List<WorkflowRunResp.StepResp> steps = new ArrayList<>();
        for (Map<String, Object> step : copySteps(storedSteps)) {
            WorkflowRunResp.StepResp item = new WorkflowRunResp.StepResp();
            item.setNodeId(text(step.get("nodeId")));
            item.setRunId(runId);
            item.setTitle(text(step.get("title")));
            item.setStepType(text(step.get("stepType")));
            item.setStatus(text(step.get("status")));
            item.setProgress(intValue(step.get("progress")));
            item.setCreditCost(longValue(step.get("creditCost")));
            item.setResolvedPrompt(text(step.get("resolvedPrompt")));
            item.setTaskId(longValue(step.get("taskId")));
            item.setErrorMessage(text(step.get("error")));
            List<WorkflowRunResp.OutputResp> outputs = new ArrayList<>();
            if (step.get("outputs") instanceof List<?> raw) {
                for (Object output : raw) {
                    if (!(output instanceof Map<?, ?> map)) {
                        continue;
                    }
                    WorkflowRunResp.OutputResp result = new WorkflowRunResp.OutputResp();
                    result.setType(text(map.get("type")));
                    result.setStorageKey(text(map.get("storageKey")));
                    result.setUrl(MediaUrls.of(result.getStorageKey()));
                    result.setValue(text(map.get("value")));
                    result.setPosterUrl(MediaUrls.of(text(map.get("posterKey"))));
                    if (map.get("metadata") instanceof Map<?, ?> metadata) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        metadata.forEach((key, value) -> values.put(String.valueOf(key), value));
                        result.setMetadata(values);
                    }
                    outputs.add(result);
                }
            }
            if (outputs.isEmpty() && TaskStatus.SUCCEEDED.equals(item.getStatus())
                    && text(step.get("outputStorageKey")) != null) {
                WorkflowRunResp.OutputResp legacy = new WorkflowRunResp.OutputResp();
                legacy.setType(text(step.get("category")));
                legacy.setStorageKey(text(step.get("outputStorageKey")));
                legacy.setUrl(MediaUrls.of(legacy.getStorageKey()));
                legacy.setPosterUrl(MediaUrls.of(text(step.get("posterKey"))));
                outputs.add(legacy);
            }
            item.setOutputs(outputs);
            steps.add(item);
        }
        return steps;
    }

    private static List<Map<String, Object>> copySteps(List<Map<String, Object>> steps) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (steps == null) {
            return copy;
        }
        for (Map<String, Object> step : steps) {
            copy.add(new LinkedHashMap<>(step));
        }
        return copy;
    }

    private static boolean depsSucceeded(Map<String, Object> step, List<Map<String, Object>> steps) {
        for (String depId : strings(step.get("dependsOn"))) {
            boolean ok = steps.stream().anyMatch(item -> depId.equals(text(item.get("nodeId")))
                    && TaskStatus.SUCCEEDED.equals(text(item.get("status"))));
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean markBlockedDependents(List<Map<String, Object>> steps) {
        boolean changed = false;
        for (Map<String, Object> step : steps) {
            if (!TaskStatus.PENDING.equals(text(step.get("status")))) {
                continue;
            }
            for (String depId : strings(step.get("dependsOn"))) {
                Map<String, Object> dependency = steps.stream()
                        .filter(item -> depId.equals(text(item.get("nodeId")))).findFirst().orElse(null);
                if (dependency != null && (TaskStatus.FAILED.equals(text(dependency.get("status")))
                        || "BLOCKED".equals(text(dependency.get("status"))))) {
                    step.put("status", "BLOCKED");
                    step.put("error", "上游节点「" + text(dependency.get("title")) + "」未成功");
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    private static boolean allTerminal(List<Map<String, Object>> steps) {
        return steps.stream().allMatch(step -> TaskStatus.SUCCEEDED.equals(text(step.get("status")))
                || TaskStatus.FAILED.equals(text(step.get("status")))
                || "BLOCKED".equals(text(step.get("status"))));
    }

    private static String aggregateErrors(List<Map<String, Object>> steps) {
        List<String> errors = steps.stream()
                .filter(step -> TaskStatus.FAILED.equals(text(step.get("status"))))
                .map(step -> text(step.get("error")))
                .filter(error -> error != null)
                .distinct().toList();
        if (!errors.isEmpty()) {
            return clip(String.join("; ", errors));
        }
        return steps.stream().filter(step -> "BLOCKED".equals(text(step.get("status"))))
                .map(step -> text(step.get("error"))).filter(error -> error != null)
                .findFirst().orElse(null);
    }

    private static int count(List<Map<String, Object>> steps, String status) {
        return (int) steps.stream().filter(step -> status.equals(text(step.get("status")))).count();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static String categoryLabel(String category) {
        if (ModelCategory.VIDEO.equals(category)) {
            return "视频";
        }
        if (ModelCategory.AUDIO.equals(category)) {
            return "音频";
        }
        return "图像";
    }

    private static String clip(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.length() <= 1000 ? trimmed : trimmed.substring(0, 1000);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer intValue(Object value) {
        Long number = longValue(value);
        return number == null ? null : number.intValue();
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
