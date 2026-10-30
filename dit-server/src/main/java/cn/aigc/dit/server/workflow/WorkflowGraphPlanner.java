package cn.aigc.dit.server.workflow;

import cn.aigc.dit.common.exception.BizException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从画布 JSON 抽出可执行的文本、生成与剪辑节点，并按连线做拓扑排序。
 * ComfyUI 采样节点不在此执行。
 */
public final class WorkflowGraphPlanner {

    public static final int MAX_NODES = 200;
    public static final int MAX_RUNNABLE = 20;

    private static final Map<String, String> CATEGORY_BY_KIND = Map.of(
            "ImageGenerator", "image",
            "VideoGenerator", "video",
            "AudioGenerator", "audio");

    private WorkflowGraphPlanner() {
    }

    public record Ref(String type, String storageKey) {
    }

    public record Output(String name, String value) {
    }

    public record Clip(String sourceNodeId, Integer outputIndex, int order,
                       double trimStart, Double trimEnd, boolean muted, double volume) {
    }

    private record ClipKey(String sourceNodeId, Integer outputIndex) {
    }

    public record Step(
            String nodeId,
            String title,
            String kind,
            String category,
            String prompt,
            String ratio,
            String resolution,
            String quality,
            Integer durationSec,
            Integer copies,
            Double speed,
            String modelName,
            boolean frameMode,
            boolean videoRefMode,
            String firstFrameKey,
            String lastFrameKey,
            List<String> dependsOn,
            List<Ref> refs,
            String stepType,
            String resolvedPrompt,
            List<Output> outputs,
            List<Clip> clips,
            Integer outputIndex,
            Map<String, Object> audio) {
    }

    public static List<Step> plan(Map<String, Object> graph) {
        return plan(graph, null);
    }

    /** Read-only recovery validates each upstream branch independently; an empty plan marks an invalid node. */
    public static Map<String, List<Step>> recoveryBranches(Map<String, Object> graph) {
        List<Map<String, Object>> nodes = mapList(map(graph).get("nodes"));
        if (nodes.size() > MAX_NODES) throw new BizException("画布节点过多");
        List<Map<String, Object>> edges = mapList(map(graph).get("edges"));
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = text(node.get("id"));
            if (id != null) byId.put(id, node);
        }
        Map<String, List<Step>> branches = new LinkedHashMap<>();
        for (var entry : byId.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> data = map(entry.getValue().get("data"));
            String kind = text(data.get("kind"));
            if (Boolean.FALSE.equals(data.get("enabled")) || kind == null
                    || (!CATEGORY_BY_KIND.containsKey(kind) && !textKind(kind) && !"VideoEditing".equals(kind))) continue;
            // Keep the terminal-node rule even when its outgoing edge lies outside this upstream slice.
            if ("VideoEditing".equals(kind) && edges.stream().anyMatch(edge -> id.equals(text(edge.get("source"))))) {
                branches.put(id, List.of());
                continue;
            }
            Set<String> upstream = new LinkedHashSet<>(sourceDistances(id, byId, edges).keySet());
            upstream.add(id);
            Map<String, Object> branch = Map.of(
                    "nodes", nodes.stream().filter(node -> upstream.contains(text(node.get("id")))).toList(),
                    "edges", edges.stream().filter(edge -> upstream.contains(text(edge.get("source")))
                            && upstream.contains(text(edge.get("target")))).toList());
            try {
                branches.put(id, plan(branch, id));
            } catch (BizException invalidBranch) {
                branches.put(id, List.of());
            }
        }
        return branches;
    }

    public static List<Step> plan(Map<String, Object> graph, String onlyNodeId) {
        if (graph == null) {
            throw new BizException("画布不能为空");
        }
        List<Map<String, Object>> nodes = mapList(graph.get("nodes"));
        if (nodes.size() > MAX_NODES) {
            throw new BizException("画布节点过多");
        }
        List<Map<String, Object>> edges = mapList(graph.get("edges"));
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = text(node.get("id"));
            if (id != null) {
                byId.put(id, node);
            }
        }
        for (Map<String, Object> edge : edges) {
            Map<String, Object> source = byId.get(text(edge.get("source")));
            if (source != null && "VideoEditing".equals(text(map(source.get("data")).get("kind")))) {
                throw new BizException("视频剪辑是终端节点，不能连接下游");
            }
        }
        List<Step> steps = new ArrayList<>();
        int generatorCount = 0;
        for (Map<String, Object> node : nodes) {
            Map<String, Object> data = map(node.get("data"));
            String kind = text(data.get("kind"));
            String category = kind == null ? null : CATEGORY_BY_KIND.get(kind);
            if (category == null && !textKind(kind) && !"VideoEditing".equals(kind)) {
                continue;
            }
            if (Boolean.FALSE.equals(data.get("enabled"))) {
                continue;
            }
            String nodeId = text(node.get("id"));
            if (nodeId == null) {
                continue;
            }
            if (category != null) {
                generatorCount++;
            }
            steps.add(toStep(nodeId, data, category, edges, byId));
        }
        if (steps.isEmpty()) {
            throw new BizException("当前画布没有可运行的节点");
        }
        if (generatorCount > MAX_RUNNABLE) {
            throw new BizException("单次运行最多 " + MAX_RUNNABLE + " 个生成节点");
        }
        List<Step> ordered = topological(steps);
        List<Step> selected = onlyNodeId == null || onlyNodeId.isBlank()
                ? ordered : filterNode(ordered, onlyNodeId.trim());
        return resolve(selected, byId, edges);
    }

    private static Step toStep(String nodeId, Map<String, Object> data, String category,
                               List<Map<String, Object>> edges, Map<String, Map<String, Object>> byId) {
        Map<String, Object> media = map(data.get("media"));
        String prompt = text(media.get("prompt"));
        if (prompt != null && prompt.length() > 4096) {
            prompt = prompt.substring(0, 4096);
        }
        Set<String> dependsOn = new LinkedHashSet<>();
        List<Ref> refs = new ArrayList<>();
        collectInputs(nodeId, category, edges, byId, dependsOn, refs, new LinkedHashSet<>());
        // 直接附加在节点上的参考素材（前端参考素材弹框「素材库/本地上传」，存于 media.referenceAssets，
        // 不落画布节点/连线）：与连线的静态引用同口径，排在连线引用之后
        for (Map<String, Object> raw : mapList(media.get("referenceAssets"))) {
            String key = firstNonBlank(text(raw.get("storageKey")), text(raw.get("assetKey")));
            if (!storageKey(key)) {
                continue;
            }
            String type = text(raw.get("type"));
            if (type == null) {
                type = "image";
            }
            type = type.toLowerCase();
            if (category == null || acceptRef(category, type)) {
                refs.add(new Ref(type, key));
            }
        }
        boolean video = "video".equals(category);
        String mode = text(media.get("mode"));
        String kind = text(data.get("kind"));
        String stepType = textKind(kind) ? "TEXT"
                : "VideoEditing".equals(kind) ? "EDIT" : "GENERATE";
        if ("audio".equals(category) && "uploaded".equals(text(media.get("selectedAudioSource")))) {
            String key = text(map(media.get("uploadedAudio")).get("storageKey"));
            if (!storageKey(key)) throw new BizException("节点「" + nodeId + "」上传音频缺少有效存储地址");
            stepType = "ASSET";
            refs.add(new Ref("audio", key));
        }
        Map<String, Object> audio = new LinkedHashMap<>();
        if ("audio".equals(category)) {
            audio.put("audioMode", List.of("music", "音乐", "AI 音乐").contains(String.valueOf(media.get("tab"))) ? "music" : "speech");
            audio.put("voiceId", firstNonBlank(text(media.get("voiceId")), text(media.get("voice"))));
            audio.put("emotion", text(media.get("emotion")));
            audio.put("lyrics", text(media.get("lyrics")));
            audio.put("instrumental", !Boolean.FALSE.equals(media.get("instrumental")));
            // AI 音乐风格与时长（需求 4.2.4.4）：仅透传给模型适配层，未接模型的字段会被忽略
            audio.put("musicStyle", text(media.get("musicStyle")));
            audio.put("musicDuration", media.get("musicDuration") == null ? null : doubleValue(media.get("musicDuration"), null));
        }
        List<Output> outputs = "TEXT".equals(stepType) ? textOutputs(data) : List.of();
        return new Step(
                nodeId,
                titleOf(data),
                kind,
                category,
                prompt,
                clip(text(media.get("ratio")), 32),
                clip(text(media.get("resolution")), 32),
                quality(text(media.get("quality"))),
                video ? intValue(media.getOrDefault("durationSec", media.get("duration")), 5, 1, 30) : null,
                intValue(media.get("copies"), 1, 1, 4),
                speed(media.get("speed")),
                "music".equals(audio.get("audioMode"))
                        ? firstNonBlank(text(media.get("musicModel")), text(media.get("model"))) : text(media.get("model")),
                video && isFrameMode(mode),
                video && isVideoRefMode(mode),
                // 参考帧（B2）：显式首/尾帧图框直传（media.frames.firstKey / lastKey，均为图片）
                frameKey(media, "firstKey"),
                frameKey(media, "lastKey"),
                List.copyOf(dependsOn),
                List.copyOf(refs),
                stepType,
                null,
                outputs,
                List.of(),
                intValue(media.getOrDefault("selectedOutputIndex", media.get("outputIndex")), 0, 0, Integer.MAX_VALUE), audio);
    }

    private static void collectInputs(String nodeId, String category, List<Map<String, Object>> edges,
                                      Map<String, Map<String, Object>> nodes, Set<String> dependsOn,
                                      List<Ref> refs, Set<String> visited) {
        if (!visited.add(nodeId)) return;
        for (Map<String, Object> edge : edges) {
            if (!nodeId.equals(text(edge.get("target")))) continue;
            String sourceId = text(edge.get("source"));
            Map<String, Object> source = nodes.get(sourceId);
            if (source == null) continue;
            Map<String, Object> data = map(source.get("data"));
            if (Boolean.FALSE.equals(data.get("enabled"))) continue;
            String kind = text(data.get("kind"));
            if ((kind != null && CATEGORY_BY_KIND.containsKey(kind)) || textKind(kind)) {
                dependsOn.add(sourceId);
                continue;
            }
            Ref ref = staticRef(data);
            if (ref != null) {
                if (category != null && acceptRef(category, ref.type())) refs.add(ref);
            } else {
                collectInputs(sourceId, category, edges, nodes, dependsOn, refs, visited);
            }
        }
    }

    private static List<Step> resolve(List<Step> ordered, Map<String, Map<String, Object>> nodes,
                                      List<Map<String, Object>> edges) {
        Map<String, Step> resolved = new LinkedHashMap<>();
        Map<String, Step> selected = new LinkedHashMap<>();
        for (Step step : ordered) {
            selected.put(step.nodeId(), step);
        }
        for (Step step : ordered) {
            Map<String, Object> data = map(nodes.get(step.nodeId()).get("data"));
            try {
                if ("TEXT".equals(step.stepType())) {
                    List<Output> outputs = new ArrayList<>();
                    for (Output output : step.outputs()) {
                        String value = WorkflowVariables.resolve(output.value(), scopes(step, nodes, resolved, edges)).value();
                        outputs.add(new Output(output.name(), value));
                    }
                    resolved.put(step.nodeId(), withResolved(step, null, List.copyOf(outputs), List.of()));
                } else if ("GENERATE".equals(step.stepType())) {
                    // 提示词来源（需求：文本节点可连接任意生成节点作为提示词补充）：
                    // 1) 节点自填提示词为主；2) 上游文本节点的内容作为补充追加；
                    // 3) 自身为空时，先取上游文本节点，再退回上游生成节点已解析的提示词（图片→视频/音频复用画面描述）。
                    String prompt = step.prompt();
                    List<String> supplements = textSupplements(step, resolved);
                    if (!supplements.isEmpty()) {
                        prompt = prompt == null ? supplements.get(0) : prompt + "\n" + String.join("\n", supplements);
                    }
                    if (prompt == null) {
                        prompt = inheritedPrompt(step, resolved);
                    }
                    if (prompt == null) {
                        throw new BizException("节点「" + step.title() + "」缺少提示词");
                    }
                    String value = WorkflowVariables.resolve(clip(prompt, 4096), scopes(step, nodes, resolved, edges)).value();
                    resolved.put(step.nodeId(), withResolved(step, value, List.of(), List.of()));
                } else if ("ASSET".equals(step.stepType())) {
                    resolved.put(step.nodeId(), step);
                } else {
                    List<Clip> clips = editClips(step, data, selected);
                    resolved.put(step.nodeId(), withResolved(step, null, List.of(), clips));
                }
            } catch (BizException error) {
                throw new BizException("节点「" + step.title() + "」(" + step.nodeId() + "): " + error.getMessage());
            }
        }
        return List.copyOf(resolved.values());
    }

    private static List<WorkflowVariables.Scope> scopes(Step step,
            Map<String, Map<String, Object>> nodes, Map<String, Step> resolved, List<Map<String, Object>> edges) {
        List<WorkflowVariables.Scope> scopes = new ArrayList<>();
        scopes.add(new WorkflowVariables.Scope(0, step.nodeId(), variablesOf(map(nodes.get(step.nodeId()).get("data")))));
        for (var entry : sourceDistances(step.nodeId(), nodes, edges).entrySet()) {
            String id = entry.getKey();
            Step source = resolved.get(id);
            if (source == null || !"TEXT".equals(source.stepType())) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            source.outputs().forEach(output -> values.put(output.name(), output.value()));
            scopes.add(new WorkflowVariables.Scope(entry.getValue(), id, values));
        }
        return scopes;
    }

    /** Variable priority follows shortest paths in the original graph, not flattened execution dependencies. */
    private static Map<String, Integer> sourceDistances(String target, Map<String, Map<String, Object>> nodes,
                                                       List<Map<String, Object>> edges) {
        Map<String, Integer> distances = new LinkedHashMap<>();
        distances.put(target, 0);
        ArrayDeque<String> pending = new ArrayDeque<>(List.of(target));
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            for (Map<String, Object> edge : edges) {
                if (!id.equals(text(edge.get("target")))) continue;
                String source = text(edge.get("source"));
                Map<String, Object> node = nodes.get(source);
                if (node == null || Boolean.FALSE.equals(map(node.get("data")).get("enabled")) || distances.containsKey(source)) continue;
                distances.put(source, distances.get(id) + 1);
                pending.addLast(source);
            }
        }
        distances.remove(target);
        return distances;
    }

    private static Map<String, String> variablesOf(Map<String, Object> data) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.putAll(stringValues(data.get("variables")));
        variables.putAll(stringValues(map(data.get("media")).get("variables")));
        if (textKind(text(data.get("kind")))) {
            for (Map<String, Object> field : mapList(data.get("fields"))) {
                String name = text(field.get("label"));
                String value = text(field.get("value"));
                if (name != null && value != null && !"内容".equals(name) && !"说明".equals(name)) {
                    variables.putIfAbsent(name, value);
                }
            }
        }
        return variables;
    }

    private static Map<String, String> stringValues(Object value) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map(value).entrySet()) {
            String key = text(entry.getKey());
            String item = text(entry.getValue());
            if (key != null && item != null) {
                values.put(key, item);
            }
        }
        return values;
    }

    private static List<Output> textOutputs(Map<String, Object> data) {
        List<Output> outputs = new ArrayList<>();
        for (Map<String, Object> field : mapList(data.get("fields"))) {
            String value = text(field.get("value"));
            if (value != null) {
                outputs.add(new Output(firstNonBlank(text(field.get("label")), "TEXT"), value));
            }
        }
        String prompt = text(map(data.get("media")).get("prompt"));
        if (prompt != null && "MarkdownNote".equals(text(data.get("kind")))) {
            String name = outputs.isEmpty() ? "提示词" : outputs.get(0).name();
            if (!outputs.isEmpty()) outputs.remove(0);
            outputs.add(0, new Output(name, prompt));
        }
        for (var entry : variablesOf(data).entrySet()) {
            if (outputs.stream().noneMatch(output -> output.name().equals(entry.getKey()))) {
                outputs.add(new Output(entry.getKey(), entry.getValue()));
            }
        }
        return List.copyOf(outputs);
    }

    private static boolean textKind(String kind) {
        return "MarkdownNote".equals(kind) || "DataTable".equals(kind);
    }

    private static List<Clip> editClips(Step step, Map<String, Object> data, Map<String, Step> selected) {
        List<String> videos = new ArrayList<>();
        visitVideos(step.nodeId(), selected, new LinkedHashSet<>(), videos);
        if (videos.isEmpty()) {
            throw new BizException("剪辑节点「" + step.title() + "」缺少上游视频生成节点");
        }
        List<Clip> clips = new ArrayList<>();
        Set<ClipKey> used = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (Map<String, Object> raw : mapList(map(data.get("editing")).get("clips"))) {
            String sourceId = text(raw.get("sourceNodeId"));
            if (sourceId == null || !videos.contains(sourceId)) {
                continue;
            }
            // 片段启用开关（需求 4.2.5.3）：禁用片段不参与成片合成
            if (Boolean.FALSE.equals(raw.get("enabled"))) {
                disabled.add(sourceId);
                continue;
            }
            Integer outputIndex = intValue(raw.get("outputIndex"), selected.get(sourceId).outputIndex(),
                    0, Integer.MAX_VALUE);
            if (!used.add(new ClipKey(sourceId, outputIndex))) {
                continue;
            }
            double trimStart = doubleValue(raw.get("trimStart"), 0.0);
            Double trimEnd = doubleValue(raw.get("trimEnd"), null);
            if (!Double.isFinite(trimStart) || trimStart < 0 || (trimEnd != null && (!Double.isFinite(trimEnd) || trimEnd <= trimStart))) {
                throw new BizException("视频片段裁剪范围无效：" + sourceId);
            }
            clips.add(new Clip(sourceId, outputIndex,
                    intValue(raw.get("order"), clips.size(), 0, Integer.MAX_VALUE),
                    trimStart, trimEnd, Boolean.TRUE.equals(raw.get("muted")),
                    Math.max(0, Math.min(2, doubleValue(raw.get("volume"), 1.0)))));
        }
        clips.sort(Comparator.comparingInt(Clip::order));
        for (String sourceId : videos) {
            // 显式禁用的片段不再被自动补回，保证"禁用"在成片中真正生效
            if (disabled.contains(sourceId)) {
                continue;
            }
            Integer outputIndex = selected.get(sourceId).outputIndex();
            if (used.stream().noneMatch(key -> key.sourceNodeId().equals(sourceId))) {
                clips.add(new Clip(sourceId, outputIndex, clips.size(), 0, null, false, 1));
            }
        }
        if (clips.isEmpty()) {
            throw new BizException("剪辑节点「" + step.title() + "」的所有片段均已禁用，请至少启用一个片段");
        }
        return List.copyOf(clips);
    }

    private static void visitVideos(String id, Map<String, Step> selected, Set<String> visited, List<String> videos) {
        if (!visited.add(id)) return;
        Step source = selected.get(id);
        if (source == null) return;
        for (String dep : source.dependsOn()) visitVideos(dep, selected, visited, videos);
        if ("VideoGenerator".equals(source.kind())) videos.add(id);
    }

    private static Double doubleValue(Object value, Double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** 上游文本节点输出的正文（按连线顺序），作为当前节点的提示词补充。 */
    private static List<String> textSupplements(Step step, Map<String, Step> resolved) {
        List<String> values = new ArrayList<>();
        for (String depId : step.dependsOn()) {
            Step parent = resolved.get(depId);
            if (parent == null || !"TEXT".equals(parent.stepType()) || parent.outputs().isEmpty()) {
                continue;
            }
            String value = text(parent.outputs().get(0).value());
            if (value != null && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    /** 自身没有提示词时，复用直接上游生成节点的提示词（图片→视频、图片→音频的画面描述）。 */
    private static String inheritedPrompt(Step step, Map<String, Step> resolved) {
        for (String depId : step.dependsOn()) {
            Step parent = resolved.get(depId);
            if (parent == null || !"GENERATE".equals(parent.stepType())) {
                continue;
            }
            String value = text(parent.resolvedPrompt());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Step withResolved(Step step, String resolvedPrompt, List<Output> outputs, List<Clip> clips) {
        return new Step(step.nodeId(), step.title(), step.kind(), step.category(), step.prompt(),
                step.ratio(), step.resolution(), step.quality(), step.durationSec(), step.copies(),
                step.speed(), step.modelName(), step.frameMode(), step.videoRefMode(),
                step.firstFrameKey(), step.lastFrameKey(), step.dependsOn(), step.refs(),
                step.stepType(), resolvedPrompt, outputs, clips, step.outputIndex(), step.audio());
    }

    private static List<Step> topological(List<Step> steps) {
        Map<String, Step> byId = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (Step step : steps) {
            byId.put(step.nodeId(), step);
            indegree.put(step.nodeId(), 0);
            outgoing.put(step.nodeId(), new ArrayList<>());
        }
        for (Step step : steps) {
            for (String dep : step.dependsOn()) {
                if (dep.equals(step.nodeId())) {
                    throw new BizException("工作流存在循环依赖");
                }
                if (!byId.containsKey(dep)) {
                    continue;
                }
                outgoing.get(dep).add(step.nodeId());
                indegree.merge(step.nodeId(), 1, Integer::sum);
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Step step : steps) {
            if (indegree.get(step.nodeId()) == 0) {
                ready.add(step.nodeId());
            }
        }
        List<Step> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            Step step = byId.get(id);
            List<String> deps = step.dependsOn().stream().filter(byId::containsKey).toList();
            ordered.add(deps.equals(step.dependsOn()) ? step : copy(step, deps));
            for (String next : outgoing.get(id)) {
                int left = indegree.merge(next, -1, Integer::sum);
                if (left == 0) {
                    ready.add(next);
                }
            }
        }
        if (ordered.size() != steps.size()) {
            throw new BizException("工作流存在循环依赖");
        }
        return ordered;
    }

    private static List<Step> filterNode(List<Step> ordered, String nodeId) {
        Map<String, Step> byId = new LinkedHashMap<>();
        for (Step step : ordered) {
            byId.put(step.nodeId(), step);
        }
        if (!byId.containsKey(nodeId)) {
            throw new BizException("该节点不能单独生成");
        }
        Set<String> keep = new LinkedHashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(nodeId);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!keep.add(id)) {
                continue;
            }
            for (String dep : byId.get(id).dependsOn()) {
                if (byId.containsKey(dep)) {
                    stack.push(dep);
                }
            }
        }
        List<Step> selected = new ArrayList<>();
        for (Step step : ordered) {
            if (!keep.contains(step.nodeId())) {
                continue;
            }
            List<String> deps = step.dependsOn().stream().filter(keep::contains).toList();
            selected.add(deps.equals(step.dependsOn()) ? step : copy(step, deps));
        }
        return selected;
    }

    private static Step copy(Step step, List<String> dependsOn) {
        return new Step(step.nodeId(), step.title(), step.kind(), step.category(), step.prompt(),
                step.ratio(), step.resolution(), step.quality(), step.durationSec(), step.copies(),
                step.speed(), step.modelName(), step.frameMode(), step.videoRefMode(),
                step.firstFrameKey(), step.lastFrameKey(), List.copyOf(dependsOn), step.refs(),
                step.stepType(), step.resolvedPrompt(), step.outputs(), step.clips(), step.outputIndex(), step.audio());
    }

    /** 参考帧图框取值（media.frames.firstKey / lastKey）：仅认合法存储 key，其余当未设置。 */
    private static String frameKey(Map<String, Object> media, String field) {
        return storageKey(text(map(media.get("frames")).get(field))) ? text(map(media.get("frames")).get(field)) : null;
    }

    /**
     * 首尾帧档位判定（i2v）。显式白名单而非 {@code contains("首尾")}：
     * 前端档位名改成「参考帧」后仍要认旧的「首尾帧」，两种写法都放行。
     */
    static boolean isFrameMode(String mode) {
        if (mode == null) {
            return false;
        }
        String value = mode.trim();
        return "首尾帧".equals(value) || "参考帧".equals(value);
    }

    /**
     * 参考视频档位判定（r2v + 视频素材整段透传）。与首尾帧互斥：
     * 参考帧只吃图片，首尾帧的起止画面是硬约束，参考视频则保留上游视频的运动信息。
     */
    static boolean isVideoRefMode(String mode) {
        if (mode == null) {
            return false;
        }
        return "参考视频".equals(mode.trim());
    }

    private static Ref staticRef(Map<String, Object> data) {
        String key = firstNonBlank(text(data.get("storageKey")), text(data.get("assetKey")));
        if (!storageKey(key)) {
            return null;
        }
        String type = text(data.get("assetType"));
        if (type == null) {
            type = "image";
        }
        return new Ref(type.toLowerCase(), key);
    }

    private static boolean acceptRef(String category, String refType) {
        if ("audio".equals(category)) {
            // 音频节点可连线图片/视频作为「画面参考」（对应 visual 端口）；音频适配器按需忽略视觉素材，
            // 但依赖关系与提示词兜底（复用画面描述）依赖这条引用成立
            return "image".equals(refType) || "video".equals(refType);
        }
        if ("image".equals(category)) {
            return "image".equals(refType);
        }
        return "image".equals(refType) || "video".equals(refType) || "audio".equals(refType);
    }

    public static boolean storageKey(String key) {
        return key != null && key.contains("/") && !key.startsWith("/") && !key.startsWith("blob:");
    }

    private static String titleOf(Map<String, Object> data) {
        String title = text(data.get("title"));
        return title == null ? "生成节点" : title;
    }

    private static String quality(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "高" -> "HIGH";
            case "中" -> "MEDIUM";
            case "低" -> "LOW";
            default -> value;
        };
    }

    private static Double speed(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double speed = number.doubleValue();
        if (speed < 0.25 || speed > 4) {
            return null;
        }
        return speed;
    }

    private static Integer intValue(Object value, Integer fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            String digits = String.valueOf(value).replaceAll("[^0-9-]", "");
            if (digits.isBlank() || "-".equals(digits)) {
                return fallback;
            }
            try {
                parsed = Integer.parseInt(digits);
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?>) {
                result.add(map(item));
            }
        }
        return result;
    }
}
