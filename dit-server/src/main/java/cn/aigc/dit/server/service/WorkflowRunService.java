package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.ark.ArkRequestMapper;
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
    /** 上游产物落盘等待上限：超过即判定素材缺失，避免把「文件还没写完」当成生成失败白扣积分。 */
    private static final long REF_WAIT_MILLIS = 90_000L;
    /**
     * 步骤上的「用户已请求取消」标记（毫秒时间戳）。
     * 取消与定时推进存在竞态：cancelTaskIfActive 要等 worker 任务锁，期间 gen_task 可能仍是 RUNNING。
     * 有了这个标记，refreshTasks 就不会把节点从 CANCELLED 刷回 RUNNING（否则表现为「停止后几秒又变成生成中」）。
     */
    private static final String CANCEL_REQUESTED_AT = "cancelRequestedAt";
    /**
     * 无生成任务、却长期停在中间态的步骤（SUBMITTING/EDITING/QUEUED）容忍时长。
     * 只有会连真实上游的推进才会把步骤带出这些中间态；一旦进程死在「写状态 → 拿到 taskId」之间，
     * 步骤就会永远卡住，整个 run 跟着一直 RUNNING，前端右上角就一直显示「停止」。
     */
    private static final long STEP_STUCK_MILLIS = 180_000L;
    /** 运行级僵尸兜底：run 超过该时长没有任何推进，直接收敛为终态，避免永久占住前端「停止」按钮。 */
    private static final long RUN_STALE_MILLIS = 30 * 60_000L;
    /** 步骤进入中间态（SUBMITTING/EDITING/QUEUED）的时间戳落点，用于 {@link #STEP_STUCK_MILLIS} 计时。 */
    private static final String STUCK_SINCE = "stuckSince";

    private final WorkflowRunMapper workflowRunMapper;
    private final GenTaskMapper genTaskMapper;
    private final AiModelMapper aiModelMapper;
    private final GenerateService generateService;
    private final WorkflowService workflowService;
    private final WorkflowRunLock workflowRunLock;
    private final WorkflowFilmComposer filmComposer;
    private final CreationService creationService;
    private final cn.aigc.dit.storage.ObjectStorage objectStorage;
    /** 视频→视频串联时抽上游视频尾帧作下游首帧；非 final + 可选注入，不改动现有构造器。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.aigc.dit.generate.poster.PosterExtractor posterExtractor;

    public WorkflowRunResp start(WorkflowRunReq req) {
        if (req.getWorkflowId() != null) {
            workflowService.requireReadable(req.getWorkflowId());
        }
        // 宽松规划（需求 4.6.4）：整图运行时单个节点不可运行（缺提示词/缺模型/剪辑缺上游等）
        // 标记为跳过、下游连带跳过，不再整次终止；单节点运行仍按 4.6.4.1 直接抛错拦截。
        List<Step> plan = WorkflowGraphPlanner.planForRun(req.getGraph(), req.getNodeId());
        boolean singleNode = req.getNodeId() != null && !req.getNodeId().isBlank();
        List<AiModel> models = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>().eq(AiModel::getEnabled, true));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step step : plan) {
            if ("ASSET".equals(step.stepType()) && step.refs().stream().anyMatch(ref -> !ownedKey(ref.storageKey(), UserContext.requireUserId()))) {
                throw new BizException("节点「" + step.nodeId() + "」参考音频不属于当前用户");
            }
            // 模型解析失败（没有可用的分类模型）：单节点运行硬拦；整图运行转为跳过，不牵连其余节点
            String skipReason = step.skipReason();
            Long modelId = null;
            if (skipReason == null) {
                try {
                    modelId = resolveModel(step, req.getModelId(), models);
                } catch (BizException noModel) {
                    if (singleNode) throw noModel;
                    skipReason = noModel.getMessage();
                }
            }
            Map<String, Object> map = toStep(step, modelId);
            if (skipReason != null) {
                map.put("status", "SKIPPED");
                map.put("error", clip(skipReason));
            }
            steps.add(map);
        }
        // 全部生成/剪辑节点都被跳过时，本次运行没有任何可干活步骤：直接拦截并说明首个原因，
        // 避免创建一个 0 分母、瞬间「成功」却什么都没做的空运行。
        if (steps.stream().noneMatch(step -> !"SKIPPED".equals(text(step.get("status")))
                && List.of("GENERATE", "EDIT").contains(text(step.get("stepType"))))) {
            String firstReason = steps.stream()
                    .filter(step -> "SKIPPED".equals(text(step.get("status"))))
                    .map(step -> "节点「" + text(step.get("title")) + "」" + text(step.get("error")))
                    .findFirst().orElse("没有可运行的节点");
            throw new BizException("本次运行没有可执行的生成节点：" + firstReason);
        }
        WorkflowRun prior = null;
        if (req.getPreviousRunId() != null) {
            prior = requireOwned(req.getPreviousRunId());
            if (!java.util.Objects.equals(req.getWorkflowId(), prior.getWorkflowId())) {
                throw new BizException("前次运行不属于当前工作流");
            }
        }
        // 整图运行与单节点运行都复用同一工作流的历史成功产物：
        // 参数未变的节点按指纹命中历史直接复用（不重生成），只有改动过的节点与从未跑过的节点才真正执行。
        List<WorkflowRun> history = req.getWorkflowId() == null ? ownedHistoryChain(prior)
                : ownedHistory(req.getWorkflowId(), MAX_REUSE_RUNS);
        initializeReusable(steps, history, req.getNodeId(), Boolean.TRUE.equals(req.getForceRegenerate()));
        WorkflowRun run = new WorkflowRun();
        run.setWorkflowId(req.getWorkflowId());
        run.setPreviousRunId(prior == null ? null : prior.getId());
        run.setUserId(UserContext.requireUserId());
        run.setModelId(req.getModelId());
        run.setStatus(TaskStatus.RUNNING);
        run.setProgress(0);
        run.setTotal(workTotal(steps));
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

    /**
     * 用户取消运行：nodeId 为空取消整个运行（终态 CANCELLED）；
     * 带 nodeId 只取消该节点步骤，其余步骤照常推进（终态由后续推进收敛）。
     */
    public WorkflowRunResp cancelMine(Long id, String nodeId) {
        WorkflowRun run = requireOwned(id);
        if (!TaskStatus.RUNNING.equals(run.getStatus())) {
            if (nodeId != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "运行已结束");
            }
            return toResp(run);
        }
        boolean wholeRun = nodeId == null;
        try (WorkflowRunLock.Handle lock = workflowRunLock.tryAcquire(id)) {
            if (lock != null) {
                run = workflowRunMapper.selectById(id);
                if (TaskStatus.RUNNING.equals(run.getStatus())) {
                    List<Map<String, Object>> steps = copySteps(run.getSteps());
                    boolean changed = false;
                    for (Map<String, Object> step : steps) {
                        if (isTerminalStep(step)) {
                            continue;
                        }
                        if (!wholeRun && !java.util.Objects.equals(nodeId, text(step.get("nodeId")))) {
                            continue;
                        }
                        Long taskId = longValue(step.get("taskId"));
                        if (taskId != null) {
                            generateService.cancelTaskIfActive(taskId, "取消工作流退还积分");
                        }
                        step.put("status", TaskStatus.CANCELLED);
                        step.put("error", "已取消");
                        // 取消意图要落库：cancelTaskIfActive 需等 worker 任务锁，与 4 秒一次的 tick 存在竞态。
                        // 没有这个标记时 refreshTasks 会把仍在 RUNNING 的 gen_task 反过来写回 step 的 RUNNING，
                        // 用户看到「点了停止，几秒后节点又变回生成中」。
                        step.put(CANCEL_REQUESTED_AT, System.currentTimeMillis());
                        changed = true;
                    }
                    if (changed) {
                        if (wholeRun || allTerminal(steps)) {
                            finish(run, steps, TaskStatus.CANCELLED, "已取消");
                        } else {
                            run.setSteps(steps);
                            run.setProgress(workProgress(steps));
                            workflowRunMapper.updateById(run);
                        }
                    }
                }
            } else {
                // 锁被定时推进占用：先幂等取消任务（worker/推进均尊重 CANCELLED），状态由 tick 收敛。
                // 注意：这里同样要落 cancelRequestedAt，否则 tick 里的 refreshTasks 会按 gen_task 的 RUNNING
                // 把节点写回生成中，用户看到停止后节点又「活」了。
                boolean touched = false;
                List<Map<String, Object>> steps = copySteps(run.getSteps());
                for (Map<String, Object> step : steps) {
                    if (isTerminalStep(step) || !(wholeRun || java.util.Objects.equals(nodeId, text(step.get("nodeId"))))) {
                        continue;
                    }
                    Long taskId = longValue(step.get("taskId"));
                    if (taskId != null) {
                        generateService.cancelTaskIfActive(taskId, "取消工作流退还积分");
                    }
                    step.put(CANCEL_REQUESTED_AT, System.currentTimeMillis());
                    touched = true;
                }
                if (touched) {
                    run.setSteps(steps);
                    workflowRunMapper.updateById(run);
                }
                advance(id);
            }
        }
        return toResp(requireOwned(id));
    }

    private static boolean isTerminalStep(Map<String, Object> step) {
        String status = text(step.get("status"));
        return TaskStatus.SUCCEEDED.equals(status) || TaskStatus.FAILED.equals(status)
                || TaskStatus.CANCELLED.equals(status) || "BLOCKED".equals(status) || "SKIPPED".equals(status);
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
                // reusedFromRunId 必须一起拷贝：复用步骤 creditCost=0，快照缺了复用标记会被前端
                // 当成「真跑过且免费」显示 0 积分（run 详情接口有该标记，仅快照遗漏）。
                for (String field : List.of("status", "progress", "creditCost", "taskId", "error", "outputs", "outputStorageKey", "posterKey", "reusedFromRunId")) {
                    if (previous.containsKey(field)) step.put(field, previous.get(field));
                }
                sourceRuns.put(nodeId, run.getId());
                break;
            }
        }
        for (String nodeId : branches.keySet()) {
            if (!validSteps.containsKey(nodeId)) {
                Map<String, Object> idle = new LinkedHashMap<>();
                idle.put("nodeId", nodeId);
                idle.put("status", "IDLE");
                steps.add(idle);
            }
        }
        // 导入工程 / 模板实例化的产物随 graph 走（节点顶层 outputs），本地没有对应的运行历史。
        // 只要本工作流跑过其它节点（例如后加的配音），上面的指纹匹配就会给这些节点留下
        // IDLE + 空产物；直接回给前端会抹掉水合出来的视频，下一次保存把丢失写回 DB 后
        // 「另存为模板 / 模板实例化」继续继承空图（#79~#83 视频反复消失）。
        // 因此用 graph 自带的产物把这类 IDLE 步骤补成 SUCCEEDED，仅回显属于当前用户的 key。
        for (Map<String, Object> step : steps) {
            if (!"IDLE".equals(step.get("status"))) continue;
            List<Map<String, Object>> carried = carriedOutputs(workflow.getGraph(), text(step.get("nodeId")));
            if (carried.isEmpty()) continue;
            step.put("status", TaskStatus.SUCCEEDED);
            step.put("progress", 100);
            step.put("outputs", carried);
        }
        List<WorkflowRunResp.StepResp> responses = stepResponses(steps, null);
        responses.forEach(step -> {
            step.setRunId(sourceRuns.get(step.getNodeId()));
            step.setOutputs("IDLE".equals(step.getStatus()) ? List.of() : step.getOutputs().stream()
                    .filter(output -> "text".equals(output.getType()) || ownedKey(output.getStorageKey(), UserContext.requireUserId())).toList());
        });
        return new WorkflowSnapshotResp(workflowId, history.get(0).getId(), responses, active);
    }

    /**
     * 读取 graph 节点顶层 outputs（persistableWorkflowGraph 落库的 storageKey 产物），
     * 转成 step 可直接回显的 outputs。只回显 users/{当前用户}/ 下的 key，
     * 防止导入的第三方图把别人的私有对象透出来。
     */
    private static List<Map<String, Object>> carriedOutputs(Map<String, Object> graph, String nodeId) {
        if (graph == null || nodeId == null || !(graph.get("nodes") instanceof List<?> nodes)) return List.of();
        Long userId = UserContext.requireUserId();
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?, ?> node) || !nodeId.equals(text(node.get("id")))
                    || !(node.get("outputs") instanceof List<?> outputs)) continue;
            List<Map<String, Object>> carried = new ArrayList<>();
            for (Object item : outputs) {
                if (!(item instanceof Map<?, ?> output)) continue;
                String key = text(output.get("storageKey"));
                if (!ownedKey(key, userId)) continue;
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("type", text(output.get("type")));
                value.put("storageKey", key);
                if (output.get("metadata") instanceof Map<?, ?> metadata) {
                    value.put("metadata", new LinkedHashMap<>(metadata));
                }
                String poster = text(output.get("posterKey"));
                if (ownedKey(poster, userId)) value.put("posterKey", poster);
                carried.add(value);
            }
            return carried;
        }
        return List.of();
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
        // 卡在「提交中 / 合成中 / 排队中」却始终没有 taskId 的步骤直接判失败，
        // 否则 run 会永远 RUNNING：前端刷新后右上角仍显示「停止」，用户以为还在生成。
        changed |= failStuckSteps(steps);
        changed |= markBlockedDependents(steps);
        log.debug("workflow run {} pass={}", runId,
                steps.stream().map(item -> item.get("nodeId") + ":" + item.get("status") + ":" + item.get("taskId")).toList());
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
            // 上游产物落盘检查：上游任务已成功但对象还没写完时先等一会，避免下游拿空引用提交后白扣积分
            String missingRef = missingReferenceKey(step, steps);
            if (missingRef != null) {
                long since = longValue(step.get("refWaitAt")) == null ? 0L : longValue(step.get("refWaitAt"));
                long now = System.currentTimeMillis();
                if (since == 0L) {
                    step.put("refWaitAt", now);
                    step.put("error", "等待上游素材落盘");
                    changed = true;
                } else if (now - since > REF_WAIT_MILLIS) {
                    step.put("status", TaskStatus.FAILED);
                    step.put("error", "上游素材缺失：" + missingRef);
                    changed = true;
                }
                continue;
            }
            step.remove("refWaitAt");
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
                    run.setProgress(workProgress(steps));
                    workflowRunMapper.updateById(run);
                }
                try {
                    List<WorkflowFilmComposer.Clip> clips = editClips(step, steps, run.getUserId());
                    List<WorkflowFilmComposer.AudioClip> audioClips = editAudioClips(step, steps, run.getUserId());
                    String nodeId = text(step.get("nodeId"));
                    // 剪辑台画幅×清晰度 → 白名单目标尺寸（AC-014：分辨率随成片真实回写）；
                    // 画幅缺省或不在白名单时 target=null，沿用历史 1280x720 行为
                    WorkflowFilmComposer.FilmSize target = null;
                    try {
                        target = WorkflowFilmComposer.FilmSize.of(text(step.get("ratio")), text(step.get("resolution")));
                    } catch (IllegalArgumentException ignored) {
                        target = null;
                    }
                    // V2 画中画（副视频轨）：kind=video 的叠加条目在运行期解析产物 key 后交给合成器
                    List<WorkflowFilmComposer.PipClip> pipClips = editPipClips(step, steps, run.getUserId());
                    WorkflowFilmComposer.FilmOutput film = filmComposer.findExisting(run.getUserId(), run.getId(),
                            nodeId, clips, target, audioClips, editClipStyles(step, clips.size()), editOverlays(step), pipClips);
                    if (film == null) {
                        film = filmComposer.compose(run.getUserId(), run.getId(), nodeId, clips, target, audioClips,
                                editClipStyles(step, clips.size()), editOverlays(step), pipClips);
                    }
                    creationService.recordWorkflowFilm(run.getUserId(), run.getId(), text(step.get("nodeId")),
                            text(step.get("title")), film.storageKey(), film.posterKey(), film.durationSeconds());
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "film");
                    output.put("storageKey", film.storageKey());
                    output.put("posterKey", film.posterKey());
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("durationSec", film.durationSeconds());
                    if (film.width() != null && film.height() != null) {
                        metadata.put("width", film.width());
                        metadata.put("height", film.height());
                        metadata.put("resolution", film.width() + "x" + film.height());
                    }
                    output.put("metadata", metadata);
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
            run.setProgress(workProgress(steps));
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
            if ("SUBMITTING".equals(status) && withinStuckGrace(step)) {
                // 宽限期内先找回、找不到也不重提：上一次提交可能还在途，
                // 立即重提会重复扣积分；超时由 failStuckSteps 收敛为 FAILED。
                // 刚由 PENDING 置为 SUBMITTING 的步骤没有 stuckSince 标记，本轮照常提交。
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
            boolean cancelled = steps.stream().anyMatch(step -> TaskStatus.CANCELLED.equals(text(step.get("status"))));
            boolean partial = steps.stream().anyMatch(step -> TaskStatus.FAILED.equals(text(step.get("status")))
                    || "BLOCKED".equals(text(step.get("status"))));
            // 用户主动停止：既不是成功也不是失败，终态 CANCELLED（与 cancelMine 一致）
            String status = cancelled ? TaskStatus.CANCELLED : partial ? "PARTIAL_FAILED" : TaskStatus.SUCCEEDED;
            String error = cancelled ? "已取消" : partial ? aggregateErrors(steps) : null;
            finish(run, steps, status, error);
            return true;
        }
        if (run.getCreatedAt() != null
                && run.getCreatedAt().isBefore(OffsetDateTime.now().minusMinutes(RUN_STALE_MILLIS / 60_000L))) {
            // 兜底：run 存在超过 RUN_STALE_MILLIS 仍未终态，说明推进链路已经坏死（进程重启丢失、
            // 上游任务永久挂起等）。直接收敛为 PARTIAL_FAILED，避免 activeRuns 永远把它报成 RUNNING。
            for (Map<String, Object> step : steps) {
                String status = text(step.get("status"));
                if (TaskStatus.RUNNING.equals(status) || "QUEUED".equals(status)
                        || "SUBMITTING".equals(status) || "EDITING".equals(status)
                        || TaskStatus.PENDING.equals(status) || "IDLE".equals(status)) {
                    step.put("status", TaskStatus.FAILED);
                    step.put("error", "运行超时未完成");
                }
            }
            log.warn("workflow run {} exceeded {} minutes without reaching a terminal state, forcing close",
                    runId, RUN_STALE_MILLIS / 60_000L);
            finish(run, steps, "PARTIAL_FAILED", "运行超时未完成");
            return true;
        }
        if (changed) {
            run.setSteps(steps);
            run.setProgress(workProgress(steps));
            workflowRunMapper.updateById(run);
        }
        return false;
    }

    /**
     * 中间态卡死兜底：SUBMITTING/EDITING/QUEUED 且没有 taskId 的步骤，超过 {@link #STEP_STUCK_MILLIS}
     * 仍未被推进，判定为推进链路坏死，直接置 FAILED。用 run 的 createdAt 作参照，
     * 因为步骤本身不带时间戳，而 run 的开始时间就是这些中间态的上界。
     */
    private boolean failStuckSteps(List<Map<String, Object>> steps) {
        boolean changed = false;
        for (Map<String, Object> step : steps) {
            String status = text(step.get("status"));
            if (!"SUBMITTING".equals(status) && !"EDITING".equals(status) && !"QUEUED".equals(status)) {
                continue;
            }
            if (step.containsKey(CANCEL_REQUESTED_AT)) {
                continue;
            }
            if (longValue(step.get("taskId")) != null) {
                // 已有生成任务：交给 refreshTasks 按任务终态收敛，这里不抢。
                continue;
            }
            if (!step.containsKey(STUCK_SINCE)) {
                step.put(STUCK_SINCE, System.currentTimeMillis());
                changed = true;
                continue;
            }
            long since = longValue(step.get(STUCK_SINCE)) == null ? 0L : longValue(step.get(STUCK_SINCE));
            if (since > 0 && System.currentTimeMillis() - since > STEP_STUCK_MILLIS) {
                step.put("status", TaskStatus.FAILED);
                step.put("error", "生成任务提交超时，请重试");
                changed = true;
            }
        }
        return changed;
    }

    /** 「提交中」步骤是否仍在宽限期内（stuckSince 已记且未超 {@link #STEP_STUCK_MILLIS}）。 */
    private static boolean withinStuckGrace(Map<String, Object> step) {
        Long since = longValue(step.get(STUCK_SINCE));
        return since != null && System.currentTimeMillis() - since <= STEP_STUCK_MILLIS;
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
            String stepStatus = text(step.get("status"));
            if (!TaskStatus.RUNNING.equals(stepStatus) && !"QUEUED".equals(stepStatus)) {
                continue;
            }
            // 用户已点停止：以取消意图为准，不再用 gen_task 的旧状态把节点刷回生成中。
            // 否则 worker 尚未把任务落成 CANCELLED 的窗口期内，节点会反复回弹成 RUNNING。
            if (step.containsKey(CANCEL_REQUESTED_AT)) {
                if (!TaskStatus.CANCELLED.equals(stepStatus)) {
                    step.put("status", TaskStatus.CANCELLED);
                    step.put("error", "已取消");
                    changed = true;
                }
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
            if (TaskStatus.PENDING.equals(task.getStatus())) {
                if (!"QUEUED".equals(stepStatus)
                        || !java.util.Objects.equals(step.get("error"), task.getErrorMessage())
                        || (task.getProgress() != null && !task.getProgress().equals(step.get("progress")))) {
                    step.put("status", "QUEUED");
                    step.put("error", task.getErrorMessage());
                    step.put("progress", task.getProgress());
                    changed = true;
                }
                continue;
            }
            if (TaskStatus.RUNNING.equals(task.getStatus()) && !"RUNNING".equals(stepStatus)) {
                step.put("status", TaskStatus.RUNNING);
                step.put("error", null);
                changed = true;
            }
            if (TaskStatus.SUCCEEDED.equals(task.getStatus())) {
                log.info("workflow node {} task {} succeeded keys={}",
                        step.get("nodeId"), taskId, task.getOutputKeys());
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
            } else if ("CANCELLED".equals(task.getStatus())) {
                // 用户主动停止：保留 CANCELLED 语义，前端据此显示「已取消」而非「生成失败」
                step.put("status", TaskStatus.CANCELLED);
                step.put("error", "已取消");
                step.put("progress", task.getProgress());
                changed = true;
            } else if (TaskStatus.FAILED.equals(task.getStatus())) {
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
            validateReferenceCounts(refs, text(step.get("title")), referenceVideoMaxCount(step));
            log.info("workflow submit node={} refs={}", step.get("nodeId"),
                    refs.stream().map(GenerateSubmitReq.ReferenceInput::getStorageKey).toList());
            if (Boolean.TRUE.equals(step.get("frameMode"))) {
                // 参考帧（B2）：优先显式图框（media.frames），缺省回落到连线引用顺序
                // （上游视频尾帧排最前作首帧，第 2 张图作尾帧），尾帧可省略；
                // 帧素材转 FrameInput 后从 references 剔除（官方首尾帧与参考素材互斥）
                String firstFrameKey = text(step.get("firstFrameKey"));
                String lastFrameKey = text(step.get("lastFrameKey"));
                List<GenerateSubmitReq.ReferenceInput> images = firstFrameKey != null ? List.of()
                        : refs.stream()
                        .filter(ref -> ModelCategory.IMAGE.equals(ref.getType()))
                        .toList();
                if (firstFrameKey != null || !images.isEmpty()) {
                    GenerateSubmitReq.FrameInput frames = new GenerateSubmitReq.FrameInput();
                    if (firstFrameKey != null) {
                        frames.setFirstFrameKey(firstFrameKey);
                        frames.setLastFrameKey(lastFrameKey);
                    } else {
                        frames.setFirstFrameKey(images.get(0).getStorageKey());
                        if (lastFrameKey != null) {
                            frames.setLastFrameKey(lastFrameKey);
                        } else if (images.size() >= 2) {
                            frames.setLastFrameKey(images.get(1).getStorageKey());
                        }
                    }
                    req.setFrames(frames);
                    refs = refs.stream().filter(ref -> !ModelCategory.IMAGE.equals(ref.getType())).toList();
                }
            }
            if (!refs.isEmpty()) {
                req.setReferences(refs);
            }
            // 参考视频按秒计费（按模型最高档价）：上报上游视频秒数。
            // 工作流产物不在素材表，服务端实测不到时长，故用上游步骤产物里的时长兜底。
            int referenceVideoSeconds = referenceVideoSeconds(step, steps);
            if (referenceVideoSeconds > 0) {
                // D2 后端兜底：参考视频总时长按模型代际分档（Seedance 2.0 ≤15s、2.5 ≤30s），超限直接拦截运行。
                int referenceVideoMaxSeconds = referenceVideoMaxSeconds(step);
                if (referenceVideoSeconds > referenceVideoMaxSeconds) {
                    throw new BizException("参考视频档「" + step.get("title") + "」参考视频总时长 "
                            + referenceVideoSeconds + " 秒超过上限 " + referenceVideoMaxSeconds
                            + " 秒，请减少视频参考数量或缩短片段");
                }
                req.setReferenceVideoSeconds(referenceVideoSeconds);
            }
        }
        return req;
    }

    /**
     * 「参考视频」模式的上游视频合计秒数：取各上游视频产物的 metadata 时长（秒），
     * 与后端 CreditCalculator 的「参考视频按最高档价计费」口径对齐。
     */
    private int referenceVideoSeconds(Map<String, Object> step, List<Map<String, Object>> steps) {
        if (!usesVideoReference(step)) {
            return 0;
        }
        int total = 0;
        for (String depId : strings(step.get("dependsOn"))) {
            Map<String, Object> dep = steps.stream()
                    .filter(item -> depId.equals(text(item.get("nodeId")))).findFirst().orElse(null);
            if (dep == null) {
                continue;
            }
            Map<?, ?> output = selectedOutput(dep);
            String type = output == null ? text(dep.get("category")) : text(output.get("type"));
            if (type == null || !ModelCategory.VIDEO.equalsIgnoreCase(type)) {
                continue;
            }
            if (!(output != null && output.get("metadata") instanceof Map<?, ?> metadata)) {
                continue;
            }
            Double seconds = doubleValue(metadata.get("durationSec"));
            if (seconds == null) {
                Double durationMs = doubleValue(metadata.get("durationMs"));
                seconds = durationMs == null ? null : durationMs / 1000;
            }
            if (seconds != null && seconds > 0) {
                total += (int) Math.max(1, Math.round(seconds));
            }
        }
        return total;
    }

    /** 上游引用是否都已落盘；返回第一个缺失的 key，全部就绪返回 null。 */
    private String missingReferenceKey(Map<String, Object> step, List<Map<String, Object>> steps) {
        String category = text(step.get("category"));
        if (ModelCategory.AUDIO.equals(category)) {
            return null;
        }
        for (GenerateSubmitReq.ReferenceInput ref : collectRefs(step, steps, category)) {
            if (ref.getStorageKey() != null && !objectStorage.exists(ref.getStorageKey())) {
                return ref.getStorageKey();
            }
        }
        return null;
    }

    private List<GenerateSubmitReq.ReferenceInput> collectRefs(Map<String, Object> step, List<Map<String, Object>> steps, String category) {
        List<GenerateSubmitReq.ReferenceInput> refs = new ArrayList<>();
        boolean frameMode = Boolean.TRUE.equals(step.get("frameMode"));
        boolean videoRefMode = usesVideoReference(step);
        // 显式首帧图框（B2）：参考帧档不再从连线推断首帧，且不取参考素材（与参考素材互斥）。
        // 只设置尾帧时，仍允许上游视频/图片按连线推断出首帧。
        boolean explicitFrames = frameMode
                && text(step.get("firstFrameKey")) != null;
        if (step.get("staticRefs") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    addRef(refs, text(map.get("type")), text(map.get("storageKey")), category);
                }
            }
        }
        List<GenerateSubmitReq.ReferenceInput> tailFrames = new ArrayList<>();
        for (String depId : strings(step.get("dependsOn"))) {
            Map<String, Object> dep = steps.stream().filter(item -> depId.equals(text(item.get("nodeId")))).findFirst().orElse(null);
            if (dep == null) {
                continue;
            }
            Map<?, ?> output = selectedOutput(dep);
            String type = output == null ? text(dep.get("category")) : text(output.get("type"));
            String key = output == null ? text(dep.get("outputStorageKey")) : text(output.get("storageKey"));
            // 视频→视频连线：「参考视频」档（默认）整段透传给模型（保留动作/运镜）；
            // 「参考帧」档走首尾帧，上游视频抽尾帧作首帧素材（写入 tailFrames，转 FrameInput）
            if (ModelCategory.VIDEO.equals(category) && type != null
                    && ModelCategory.VIDEO.equalsIgnoreCase(type) && WorkflowGraphPlanner.storageKey(key)) {
                if (videoRefMode) {
                    addRef(refs, ModelCategory.VIDEO, key, category);
                } else {
                    addRef(tailFrames, ModelCategory.IMAGE, tailFrameKey(key), category);
                }
                continue;
            }
            addRef(refs, type, key, category);
        }
        refs.addAll(0, tailFrames);
        // 参考帧档只接受图片：视频/音频类参考素材在这里剔除（首尾帧是硬约束，与参考素材互斥）。
        if (frameMode) {
            refs = refs.stream().filter(ref -> ModelCategory.IMAGE.equals(ref.getType())).toList();
        }
        if (explicitFrames) {
            return List.of();
        }
        return refs;
    }

    /** 参考视频总时长默认上限（秒）：对齐 Seedance 2.5 的「视频 ≤10 段（总 30s）」约束；Seedance 2.0 分档为 15s（D2 后端兜底） */
    private static final int REFERENCE_VIDEO_MAX_SECONDS = 30;

    /** 参考图片数量上限：对齐前端 REFERENCE_LIMITS.IMAGE */
    private static final int REFERENCE_IMAGE_MAX_COUNT = 30;

    /** 参考视频段数默认上限：对齐前端 REFERENCE_LIMITS.VIDEO（总时长另行限制）；Seedance 2.0 分档为 3 段 */
    private static final int REFERENCE_VIDEO_MAX_COUNT = 10;

    /** 参考音频数量上限：对齐前端 REFERENCE_LIMITS.AUDIO */
    private static final int REFERENCE_AUDIO_MAX_COUNT = 10;

    /** Seedance 2.0 参考视频段数上限（官方约束：≤3 段且总 ≤15s）。 */
    private static final int REFERENCE_VIDEO_MAX_COUNT_2_0 = 3;

    /**
     * 按类型校验参考数量上限，防止绕过前端直接提交超量引用。
     * 口径与前端 REFERENCE_LIMITS 一致：图片 ≤30、视频 ≤10 段（Seedance 2.0 为 3 段）、音频 ≤10。
     */
    private void validateReferenceCounts(List<GenerateSubmitReq.ReferenceInput> refs, String nodeTitle, int videoMaxCount) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        long imageCount = refs.stream().filter(ref -> ModelCategory.IMAGE.equalsIgnoreCase(ref.getType())).count();
        long videoCount = refs.stream().filter(ref -> ModelCategory.VIDEO.equalsIgnoreCase(ref.getType())).count();
        long audioCount = refs.stream().filter(ref -> ModelCategory.AUDIO.equalsIgnoreCase(ref.getType())).count();
        if (imageCount > REFERENCE_IMAGE_MAX_COUNT) {
            throw new BizException("节点「" + nodeTitle + "」参考图片 " + imageCount
                    + " 张超过上限 " + REFERENCE_IMAGE_MAX_COUNT + " 张，请减少图片参考");
        }
        if (videoCount > videoMaxCount) {
            throw new BizException("节点「" + nodeTitle + "」参考视频 " + videoCount
                    + " 段超过上限 " + videoMaxCount + " 段，请减少视频参考");
        }
        if (audioCount > REFERENCE_AUDIO_MAX_COUNT) {
            throw new BizException("节点「" + nodeTitle + "」参考音频 " + audioCount
                    + " 条超过上限 " + REFERENCE_AUDIO_MAX_COUNT + " 条，请减少音频参考");
        }
    }

    /**
     * 参考视频守卫按模型代际分档：Seedance 2.0 官方限 ≤3 段/总 ≤15s，
     * 2.5 及其他视频模型沿用 ≤10 段/总 ≤30s。GenerateService 提交层另有同口径兜底。
     */
    private int referenceVideoMaxCount(Map<String, Object> step) {
        return isSeedance20Step(step) ? REFERENCE_VIDEO_MAX_COUNT_2_0 : REFERENCE_VIDEO_MAX_COUNT;
    }

    private int referenceVideoMaxSeconds(Map<String, Object> step) {
        return isSeedance20Step(step)
                ? ArkRequestMapper.REFERENCE_VIDEO_MAX_SECONDS_2_0
                : REFERENCE_VIDEO_MAX_SECONDS;
    }

    private boolean isSeedance20Step(Map<String, Object> step) {
        Long modelId = longValue(step.get("modelId"));
        if (modelId == null) {
            return false;
        }
        AiModel model = aiModelMapper.selectById(modelId);
        if (model == null) {
            return false;
        }
        String adapterKey = AdapterCatalog.resolveKeyForGenerate(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        return AdapterCatalog.SEEDANCE_2_0.equals(adapterKey);
    }

    /**
     * 兼容两代数据：新画布由 planner 显式写入 videoRefMode；旧运行可能没有该字段。
     * 视频节点只要不是首尾帧档，历史行为和前端默认都按「参考视频整段透传」处理。
     */
    private static boolean usesVideoReference(Map<String, Object> step) {
        if (!ModelCategory.VIDEO.equals(text(step.get("category")))
                || Boolean.TRUE.equals(step.get("frameMode"))) {
            return false;
        }
        return !Boolean.FALSE.equals(step.get("videoRefMode"));
    }

    /**
     * 上游视频的尾帧 key：已抽过（如分镜/之前运行）直接复用，否则现抽一次。
     * 抽帧失败时仍返回约定 key，让 {@link #missingReferenceKey} 按「素材未落盘」等待，而不是悄悄丢掉首帧。
     */
    private String tailFrameKey(String videoKey) {
        String expected = cn.aigc.dit.generate.poster.FfmpegPosterExtractor.toTailKey(videoKey);
        try {
            if (objectStorage.exists(expected)) {
                return expected;
            }
            if (posterExtractor != null && objectStorage.exists(videoKey)) {
                String tail = posterExtractor.extractTail(videoKey);
                if (WorkflowGraphPlanner.storageKey(tail)) {
                    return tail;
                }
            }
        } catch (RuntimeException e) {
            log.warn("workflow tail frame extract failed key={}", videoKey, e);
        }
        return expected;
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

    /**
     * 本地导入素材的 storageKey 由前端草稿透传，合成器会直接按它读对象存储。
     * 必须与 /api/media 的鉴权口径一致：只放行当前用户目录，避免构造任意 key 越权读他人文件。
     */
    private static boolean ownedStorageKey(String key, Long userId) {
        return userId != null && WorkflowGraphPlanner.storageKey(key)
                && !key.contains("..") && key.startsWith("users/" + userId + "/");
    }

    /**
     * V2 画中画叠加（副视频轨）：读 step 的 pipClips（Planner 已清洗、指纹已含），
     * 运行期解析产物 storageKey（上游步骤 outputs / 本地导入 key），非法条目静默丢弃。
     */
    private static List<WorkflowFilmComposer.PipClip> editPipClips(Map<String, Object> step,
                                                                   List<Map<String, Object>> steps,
                                                                   Long userId) {
        if (!(step.get("pipClips") instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<WorkflowFilmComposer.PipClip> pips = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> configured)) {
                continue;
            }
            Double start = doubleValue(configured.get("start"));
            Double end = doubleValue(configured.get("end"));
            if (start == null || end == null || start < 0 || end <= start) {
                continue;
            }
            // 源解析：本地导入直读；上游节点按 sourceNodeId+outputIndex 取 outputs
            String localKey = text(configured.get("localKey"));
            String sourceKey;
            if (localKey != null) {
                if (!ownedStorageKey(localKey, userId)) continue;
                sourceKey = localKey;
            } else {
                String sourceId = text(configured.get("sourceNodeId"));
                Map<String, Object> source = steps.stream()
                        .filter(entry -> sourceId != null && sourceId.equals(text(entry.get("nodeId"))))
                        .findFirst().orElse(null);
                if (source == null || !TaskStatus.SUCCEEDED.equals(text(source.get("status")))) {
                    continue;
                }
                int index = intValue(configured.get("outputIndex")) == null ? 0 : intValue(configured.get("outputIndex"));
                if (!(source.get("outputs") instanceof List<?> outputs) || index < 0 || index >= outputs.size()
                        || !(outputs.get(index) instanceof Map<?, ?> output)
                        || !ModelCategory.VIDEO.equals(text(output.get("type")))
                        || !WorkflowGraphPlanner.storageKey(text(output.get("storageKey")))) {
                    continue;
                }
                sourceKey = text(output.get("storageKey"));
            }
            double trimStart = doubleOr(configured.get("trimStart"), 0.0);
            Double trimEnd = doubleValue(configured.get("trimEnd"));
            if (trimEnd == null || trimEnd <= trimStart) continue;
            pips.add(new WorkflowFilmComposer.PipClip(start, end, sourceKey,
                    trimStart, trimEnd,
                    doubleOr(configured.get("x"), 70.0),
                    doubleOr(configured.get("y"), 25.0),
                    Math.max(5, Math.min(100, doubleOr(configured.get("scale"), 30.0))),
                    Math.max(0, Math.min(100, doubleOr(configured.get("opacity"), 100.0))),
                    Boolean.TRUE.equals(configured.get("muted"))));
        }
        return pips;
    }

    private static List<WorkflowFilmComposer.Clip> editClips(Map<String, Object> step,
                                                               List<Map<String, Object>> steps,
                                                               Long userId) {
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
            // 本地导入素材：不查上游步骤，直接按 storageKey 读（必须属于当前用户，防越权）
            String localKey = text(configuredClip.get("localKey"));
            if (localKey != null) {
                if (!ownedStorageKey(localKey, userId)) {
                    throw new IllegalArgumentException("本地素材路径无效或不属于当前用户");
                }
                double localStart = doubleValue(configuredClip.get("trimStart")) == null
                        ? 0 : doubleValue(configuredClip.get("trimStart"));
                Double localEnd = doubleValue(configuredClip.get("trimEnd"));
                if (localStart < 0 || localEnd == null || localEnd <= localStart) {
                    throw new IllegalArgumentException("本地素材裁剪范围无效");
                }
                Double localVolume = doubleValue(configuredClip.get("volume"));
                clips.add(new WorkflowFilmComposer.Clip(localKey, localStart, localEnd,
                        Boolean.TRUE.equals(configuredClip.get("muted")),
                        localVolume == null ? 1 : localVolume));
                continue;
            }
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
            // 图片产物也可作主轨片段（静帧段，14.61）：类型放行 VIDEO / IMAGE
            if (!(source.get("outputs") instanceof List<?> outputs) || index < 0 || index >= outputs.size()
                    || !(outputs.get(index) instanceof Map<?, ?> output)
                    || !(ModelCategory.VIDEO.equals(text(output.get("type"))) || ModelCategory.IMAGE.equals(text(output.get("type"))))
                    || !WorkflowGraphPlanner.storageKey(text(output.get("storageKey")))) {
                throw new IllegalArgumentException("源视频输出不存在：" + sourceId + " #" + index
                        + "（outputs=" + (source.get("outputs") instanceof List<?> rawOutputs
                                ? rawOutputs.stream().limit(index + 1).map(item -> item instanceof Map<?, ?> om
                                        ? text(om.get("type")) + ":" + text(om.get("storageKey")) : String.valueOf(item)).toList()
                                : "null") + "）");
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
            // 图片产物没有时长概念：trimEnd 缺省按 5s 静帧（与前端默认落轨时长一致）
            if (end == null && ModelCategory.IMAGE.equals(text(output.get("type")))) {
                end = start + 5.0;
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

    /**
     * 音频轨片段的运行期解析：按 sourceNodeId 找上游音频步骤（GENERATE 的音频产物或
     * ASSET 上传音频），取 outputs[outputIndex] 的 storageKey。与视频 editClips 的差异：
     * ① 不做 VIDEO 硬校验（音频源本就是 audio 类型）；② 上游缺失/未成功时静默丢弃该条
     * 而非抛错——音轨是增强项，不应阻断成片合成；③ trimEnd 缺省兜底 metadata 时长。
     */
    /**
     * 片段视觉样式（排期 #7）：读 step 的 clipStyles（与 clips 同序）。
     * 缺字段 / 长度不符时返回 null（= 无样式），合成器走旧链路，filter 与旧版逐字节一致。
     */
    private static List<WorkflowFilmComposer.ClipStyle> editClipStyles(Map<String, Object> step, int clipCount) {
        if (!(step.get("clipStyles") instanceof List<?> raw) || raw.isEmpty()) {
            return null;
        }
        List<WorkflowFilmComposer.ClipStyle> styles = new ArrayList<>();
        for (int i = 0; i < clipCount; i++) {
            Object item = i < raw.size() ? raw.get(i) : null;
            if (!(item instanceof Map<?, ?> configured)) {
                styles.add(WorkflowFilmComposer.ClipStyle.NONE);
                continue;
            }
            styles.add(new WorkflowFilmComposer.ClipStyle(
                    clampInt(configured.get("brightness"), -100, 100),
                    clampInt(configured.get("contrast"), -100, 100),
                    clampInt(configured.get("saturate"), -100, 100),
                    text(configured.get("effect")),
                    text(configured.get("transitionId")),
                    doubleValue(configured.get("transitionSeconds"))));
        }
        return styles;
    }

    /**
     * 叠加层（排期 #7）：读 step 的 overlays（文字 / 图形轨片段）。
     * start/end 为成片内绝对时间；非法条目静默丢弃，不阻断成片。
     */
    private static List<WorkflowFilmComposer.OverlayClip> editOverlays(Map<String, Object> step) {
        if (!(step.get("overlays") instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<WorkflowFilmComposer.OverlayClip> overlays = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> configured)) {
                continue;
            }
            String kind = text(configured.get("kind"));
            // V2 画中画：video 类不经此处（editPipClips 单独解析），跳过
            if (!"text".equals(kind) && !"graphic".equals(kind)) {
                continue;
            }
            Double start = doubleValue(configured.get("start"));
            Double end = doubleValue(configured.get("end"));
            if (start == null || end == null || start < 0 || end <= start) {
                continue;
            }
            String content = text(configured.get("text"));
            if ("text".equals(kind) && (content == null || content.isBlank())) {
                continue;
            }
            overlays.add(new WorkflowFilmComposer.OverlayClip(start, end, kind, content,
                    text(configured.get("color")),
                    doubleOr(configured.get("fontSize"), 16.0),
                    clampInt(configured.get("weight"), 100, 900),
                    text(configured.get("align")),
                    text(configured.get("shape")),
                    doubleOr(configured.get("x"), 50.0),
                    doubleOr(configured.get("y"), 50.0),
                    doubleOr(configured.get("scale"), 100.0),
                    doubleOr(configured.get("opacity"), 100.0)));
        }
        return overlays;
    }

    private static List<WorkflowFilmComposer.AudioClip> editAudioClips(Map<String, Object> step,
                                                                        List<Map<String, Object>> steps,
                                                                        Long userId) {
        if (!(step.get("audioClips") instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<WorkflowFilmComposer.AudioClip> audioClips = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> configured)) {
                continue;
            }
            // 本地导入音频：与视频同口径，按 storageKey 直读；非法条目静默丢弃不阻断成片
            String localKey = text(configured.get("localKey"));
            if (localKey != null) {
                if (!ownedStorageKey(localKey, userId)) continue;
                Double localStart = doubleValue(configured.get("start"));
                double clipStart = localStart == null || localStart < 0 ? 0 : localStart;
                double trimStart = doubleValue(configured.get("trimStart")) == null ? 0 : doubleValue(configured.get("trimStart"));
                Double trimEnd = doubleValue(configured.get("trimEnd"));
                if (trimEnd == null || trimEnd <= trimStart) continue;
                Double localVolume = doubleValue(configured.get("volume"));
                audioClips.add(new WorkflowFilmComposer.AudioClip(localKey, clipStart,
                        trimStart, trimEnd, Boolean.TRUE.equals(configured.get("muted")),
                        localVolume == null ? 1 : Math.max(0, Math.min(2, localVolume))));
                continue;
            }
            String sourceId = text(configured.get("sourceNodeId"));
            Map<String, Object> source = steps.stream()
                    .filter(entry -> sourceId != null && sourceId.equals(text(entry.get("nodeId"))))
                    .findFirst().orElse(null);
            if (source == null || !TaskStatus.SUCCEEDED.equals(text(source.get("status")))) {
                continue;
            }
            Integer selectedIndex = configured.get("outputIndex") == null ? 0 : intValue(configured.get("outputIndex"));
            int index = selectedIndex == null ? 0 : selectedIndex;
            if (!(source.get("outputs") instanceof List<?> outputs) || index < 0 || index >= outputs.size()
                    || !(outputs.get(index) instanceof Map<?, ?> output)
                    || !WorkflowGraphPlanner.storageKey(text(output.get("storageKey")))) {
                continue;
            }
            Double start = doubleValue(configured.get("start"));
            double clipStart = start == null || start < 0 ? 0 : start;
            double trimStart = doubleValue(configured.get("trimStart")) == null ? 0 : doubleValue(configured.get("trimStart"));
            Double trimEnd = doubleValue(configured.get("trimEnd"));
            if (trimEnd == null && output.get("metadata") instanceof Map<?, ?> metadata) {
                trimEnd = doubleValue(metadata.get("durationSec"));
                if (trimEnd == null) {
                    Double durationMs = doubleValue(metadata.get("durationMs"));
                    trimEnd = durationMs == null ? null : durationMs / 1000;
                }
            }
            if (trimEnd == null || trimEnd <= trimStart) {
                continue;
            }
            Double volume = doubleValue(configured.get("volume"));
            audioClips.add(new WorkflowFilmComposer.AudioClip(text(output.get("storageKey")), clipStart,
                    trimStart, trimEnd, Boolean.TRUE.equals(configured.get("muted")),
                    volume == null ? 1 : Math.max(0, Math.min(2, volume))));
        }
        return List.copyOf(audioClips);
    }

    private Long resolveModel(Step step, Long fallbackId, List<AiModel> models) {        if (!"GENERATE".equals(step.stepType())) {
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
        map.put("videoRefMode", step.videoRefMode());
        map.put("firstFrameKey", step.firstFrameKey());
        map.put("lastFrameKey", step.lastFrameKey());
        map.put("outputIndex", step.outputIndex());
        map.put("dependsOn", step.dependsOn());
        // 音频轨片段也进 step map：既参与运行指纹（改音轨触发重合成），也供运行期解析产物
        map.put("audioClips", step.audioClips().stream().map(clip -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceNodeId", clip.sourceNodeId());
            item.put("outputIndex", clip.outputIndex());
            item.put("start", clip.start());
            item.put("trimStart", clip.trimStart());
            item.put("trimEnd", clip.trimEnd());
            item.put("muted", clip.muted());
            item.put("volume", clip.volume());
            // 仅在本地导入片段上写：无导入素材时 step map 与旧版逐字节一致
            if (clip.localKey() != null) item.put("localKey", clip.localKey());
            return item;
        }).toList());
        map.put("clips", step.clips().stream().map(clip -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceNodeId", clip.sourceNodeId());
            item.put("outputIndex", clip.outputIndex());
            item.put("order", clip.order());
            item.put("trimStart", clip.trimStart());
            item.put("trimEnd", clip.trimEnd());
            item.put("muted", clip.muted());
            item.put("volume", clip.volume());
            if (clip.localKey() != null) item.put("localKey", clip.localKey());
            return item;
        }).toList());
        // 排期 #7：样式与叠加层参与运行指纹（改了调节/转场/文字要重合成）。
        // null / 空列表时不写字段——step map 与旧版逐字节一致。
        if (step.clipStyles() != null && !step.clipStyles().isEmpty()) {
            map.put("clipStyles", step.clipStyles().stream().map(style -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("brightness", style.brightness());
                item.put("contrast", style.contrast());
                item.put("saturate", style.saturate());
                item.put("effect", style.effect());
                item.put("transitionId", style.transitionId());
                item.put("transitionSeconds", style.transitionSeconds());
                return item;
            }).toList());
        }
        if (step.overlays() != null && !step.overlays().isEmpty()) {
            map.put("overlays", step.overlays().stream().map(overlay -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("start", overlay.start());
                item.put("end", overlay.end());
                item.put("kind", overlay.kind());                item.put("text", overlay.text());
                item.put("color", overlay.color());
                item.put("fontSize", overlay.fontSize());
                item.put("weight", overlay.weight());
                item.put("align", overlay.align());
                item.put("shape", overlay.shape());
                item.put("x", overlay.x());
                item.put("y", overlay.y());
                item.put("scale", overlay.scale());
                item.put("opacity", overlay.opacity());
                return item;
            }).toList());
        }
        // V2 画中画（副视频轨）：进 step map 参与运行指纹（改画中画触发重合成），
        // 运行期 editPipClips 读回并解析产物 key。无画中画时不写字段（与旧版逐字节一致）。
        if (step.pipClips() != null && !step.pipClips().isEmpty()) {
            map.put("pipClips", step.pipClips().stream().map(pip -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("start", pip.start());
                item.put("end", pip.end());
                item.put("sourceNodeId", pip.sourceNodeId());
                item.put("outputIndex", pip.outputIndex());
                item.put("trimStart", pip.trimStart());
                item.put("trimEnd", pip.trimEnd());
                item.put("x", pip.x());
                item.put("y", pip.y());
                item.put("scale", pip.scale());
                item.put("opacity", pip.opacity());
                item.put("muted", pip.muted());
                if (pip.localKey() != null) item.put("localKey", pip.localKey());
                return item;
            }).toList());
        }
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

    private static void initializeReusable(List<Map<String, Object>> steps, List<WorkflowRun> history, String target,
                                           boolean forceRegenerate) {
        Long userId = UserContext.requireUserId();
        for (Map<String, Object> step : steps) {
            // 已被规划为跳过的步骤不参与复用回填（如缺提示词/缺模型），保留跳过状态与原因
            if ("SKIPPED".equals(text(step.get("status")))) continue;
            if (!depsSucceeded(step, steps)) continue;
            String fingerprint = WorkflowStepFingerprint.of(step, steps);
            step.put("inputFingerprint", fingerprint);
            if (List.of("TEXT", "ASSET").contains(text(step.get("stepType")))) {
                step.put("status", TaskStatus.SUCCEEDED);
                step.put("progress", 100);
                step.put("creditCost", 0L);
                continue;
            }
            // 全部重新生成：跳过复用，所有生成/剪辑步骤都真跑（文本/已上传音频是零成本直通步骤，仍不重跑）
            if (forceRegenerate) continue;
            // 整图运行(target=null)复用所有命中指纹的节点；单节点运行仅跳过目标节点本身，确保它被执行
            if (target != null && target.equals(text(step.get("nodeId")))) continue;
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
        run.setProgress(workProgress(steps));
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
            item.setReused(step.get("reusedFromRunId") != null);
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
                if (dependency == null) continue;
                String depStatus = text(dependency.get("status"));
                // 上游被跳过（如缺模型，规划期之外的运行期跳过）：下游连带跳过而非阻塞（需求 4.6.4.2）
                if ("SKIPPED".equals(depStatus)) {
                    step.put("status", "SKIPPED");
                    step.put("error", "上游节点「" + text(dependency.get("title")) + "」未运行");
                    changed = true;
                    break;
                }
                if (TaskStatus.FAILED.equals(depStatus) || "BLOCKED".equals(depStatus)) {
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
                || TaskStatus.CANCELLED.equals(text(step.get("status")))
                || "BLOCKED".equals(text(step.get("status")))
                || "SKIPPED".equals(text(step.get("status"))));
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

    /**
     * 需要真正干活的步骤：生成（扣积分）与剪辑合成。
     * 文本节点与已上传音频是零成本直通步骤，不计入进度分母——否则工具条会显示「3/5」却只扣了 2 个节点的积分。
     */
    private static List<Map<String, Object>> workSteps(List<Map<String, Object>> steps) {
        return steps.stream().filter(step -> {
            // 被跳过的步骤本次不干活，不计入分母（需求 4.6.3.1），否则进度永远到不了 100%
            if ("SKIPPED".equals(text(step.get("status")))) {
                return false;
            }
            String type = text(step.get("stepType"));
            return "GENERATE".equals(type) || "EDIT".equals(type);
        }).toList();
    }

    private static int workTotal(List<Map<String, Object>> steps) {
        return workSteps(steps).size();
    }

    private static int workProgress(List<Map<String, Object>> steps) {
        return (int) workSteps(steps).stream()
                .filter(step -> TaskStatus.SUCCEEDED.equals(text(step.get("status")))).count();
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

    /** 解析为 int 并钳制到区间；缺省回落 0（排期 #7 的样式参数用）。 */
    private static int clampInt(Object value, int min, int max) {
        Integer parsed = intValue(value);
        int raw = parsed == null ? 0 : parsed;
        return Math.max(min, Math.min(max, raw));
    }

    /** 无法解析时回落默认值（排期 #7 的叠加层几何参数用）。 */
    private static double doubleOr(Object value, double fallback) {
        Double parsed = doubleValue(value);
        return parsed == null ? fallback : parsed;
    }
}
