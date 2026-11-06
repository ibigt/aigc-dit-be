package cn.aigc.dit.server.service;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Workflow;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.WorkflowMapper;
import cn.aigc.dit.server.model.req.WorkflowSaveReq;
import cn.aigc.dit.server.model.resp.WorkflowResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final Set<String> TYPES = Set.of("image", "video", "audio", "import", "template");

    /** ComfyUI 导入图里承载生成模型的字段名（编码器/VAE 文件不算，避免把 t5xxl、vae 混进模型链）。 */
    private static final Set<String> MODEL_FIELD_LABELS = Set.of("模型文件", "扩散模型", "LoRA 模型", "ckpt_name", "unet_name");

    private static final int MAX_OUTPUT_MODELS = 3;

    private final WorkflowMapper workflowMapper;
    private final AiModelMapper aiModelMapper;

    public List<WorkflowResp> listMine(String type, String q) {
        Long userId = UserContext.requireUserId();
        LambdaQueryWrapper<Workflow> wrapper = visible(userId);
        if (StringUtils.hasText(type) && TYPES.contains(type)) {
            wrapper.eq(Workflow::getType, type);
        }
        if (StringUtils.hasText(q)) {
            String keyword = q.trim();
            wrapper.and(nested -> nested.like(Workflow::getTitle, keyword).or().like(Workflow::getModelLabel, keyword));
        }
        wrapper.orderByDesc(Workflow::getUpdatedAt)
                .orderByDesc(Workflow::getId);
        return workflowMapper.selectList(wrapper).stream().map(item -> toResp(item, false)).toList();
    }

    public WorkflowResp getMine(Long id) {
        return toResp(requireReadable(id), true);
    }

    /**
     * 模板库列表：只返回当前用户保存的模板（type=template）。
     * 内置官方模板已下线，source=official 返回空；后续「保存为模板」写入的记录会自动出现在这里。
     * tags 为 jsonb 数组，Postgres 不支持 LIKE，需用 ::text 匹配或 jsonb 包含判断。
     */
    public List<WorkflowResp> listTemplates(String category, String q, String tag, String source) {
        if ("official".equalsIgnoreCase(StringUtils.trimWhitespace(source))) {
            return List.of();
        }
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getUserId, UserContext.requireUserId())
                .eq(Workflow::getType, "template");
        if (StringUtils.hasText(category)) {
            wrapper.eq(Workflow::getCategory, category.trim().toLowerCase());
        }
        if (StringUtils.hasText(tag)) {
            wrapper.apply("tags @> CAST({0} AS jsonb)", jsonArray(tag.trim()));
        }
        if (StringUtils.hasText(q)) {
            String keyword = q.trim();
            wrapper.and(nested -> nested.like(Workflow::getTitle, keyword)
                    .or().like(Workflow::getDescription, keyword)
                    .or().like(Workflow::getModelLabel, keyword)
                    .or().apply("tags::text LIKE {0}", "%" + keyword + "%"));
        }
        wrapper.orderByDesc(Workflow::getUpdatedAt);
        return workflowMapper.selectList(wrapper).stream().map(item -> toResp(item, false)).toList();
    }

    private static String jsonArray(String value) {
        return "[\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    /** 模板标签聚合，供前端渲染标签筛选条；内置官方模板已下线，只聚合用户自己的模板。 */
    public List<String> listTemplateTags() {
        List<Workflow> templates = workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getUserId, UserContext.requireUserId())
                .eq(Workflow::getType, "template"));
        Set<String> tags = new LinkedHashSet<>();
        templates.forEach(item -> {
            if (item.getTags() != null) {
                tags.addAll(item.getTags());
            }
        });
        return List.copyOf(tags);
    }

    public WorkflowResp create(WorkflowSaveReq req) {
        Workflow workflow = new Workflow();
        workflow.setUserId(UserContext.requireUserId());
        workflow.setBuiltin(false);
        apply(workflow, req);
        workflowMapper.insert(workflow);
        return toResp(workflow, true);
    }

    public WorkflowResp update(Long id, WorkflowSaveReq req) {
        Workflow workflow = requireOwned(id);
        apply(workflow, req);
        workflowMapper.updateById(workflow);
        return toResp(workflow, true);
    }

    public void delete(Long id) {
        Workflow workflow = requireOwned(id);
        workflowMapper.deleteById(workflow.getId());
    }

    public Workflow requireReadable(Long id) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        if (!UserContext.requireUserId().equals(workflow.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        return workflow;
    }

    private Workflow requireOwned(Long id) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null || !UserContext.requireUserId().equals(workflow.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "只能修改自己保存的工作流");
        }
        return workflow;
    }

    private void apply(Workflow workflow, WorkflowSaveReq req) {
        String type = StringUtils.hasText(req.getType()) ? req.getType().trim().toLowerCase() : "image";
        if (!TYPES.contains(type)) {
            throw new BizException("不支持的工作流类型");
        }
        Map<String, Object> graph = req.getGraph() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(req.getGraph());
        graph.putIfAbsent("nodes", List.of());
        graph.putIfAbsent("edges", List.of());
        int nodeCount = graph.get("nodes") instanceof List<?> nodes ? nodes.size() : 0;
        workflow.setTitle(req.getTitle().trim());
        workflow.setDescription(trimToNull(req.getDescription()));
        workflow.setType(type);
        workflow.setModelId(req.getModelId());
        workflow.setModelLabel(modelLabel(req.getModelId()));
        workflow.setOrigin(trimToNull(req.getOrigin()));
        workflow.setDemoKey(nodeCount == 0 ? trimToNull(req.getDemoKey()) : null);
        workflow.setSourcePath(nodeCount == 0 ? trimToNull(req.getSourcePath()) : null);
        workflow.setNodeCount(nodeCount);
        workflow.setGraph(graph);
        workflow.setTopology(buildTopology(graph));
        // 模板元信息（仅模板类型生效；普通工作流保存不携带，避免误写分类/标签）
        if ("template".equals(type)) {
            String category = StringUtils.hasText(req.getCategory()) ? req.getCategory().trim().toLowerCase() : null;
            // Set.of(...) 的 contains(null) 会抛 NPE（ImmutableCollections 禁止 null 元素），
            // category 为空时必须先判空，否则 type=template 且不带 category 的保存请求直接 500。
            workflow.setCategory(category != null && TYPES.contains(category)
                    && !"template".equals(category) && !"import".equals(category) ? category : "image");
            List<String> tags = req.getTags() == null ? List.of() : req.getTags().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(tag -> tag.length() > 24 ? tag.substring(0, 24) : tag)
                    .distinct()
                    .limit(6)
                    .toList();
            workflow.setTags(tags.isEmpty() ? null : tags);
        } else {
            workflow.setCategory(null);
            workflow.setTags(null);
        }
    }

    /** Generate compact thumbnail coordinates from the persisted canvas so every workflow card can draw its real topology. */
    private Map<String, Object> buildTopology(Map<String, Object> graph) {
        List<Map<String, Object>> graphNodes = mapValues(mapValue(graph).get("nodes"));
        List<Map<String, Object>> graphEdges = mapValues(mapValue(graph).get("edges"));
        if (graphNodes.isEmpty()) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }

        List<Map<String, Object>> topologyNodes = new ArrayList<>();
        Map<String, Integer> nodeIndexes = new LinkedHashMap<>();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        List<double[]> geometry = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> tones = new ArrayList<>();

        for (Map<String, Object> node : graphNodes) {
            Map<String, Object> position = mapValue(node.get("position"));
            Map<String, Object> style = mapValue(node.get("style"));
            Map<String, Object> data = mapValue(node.get("data"));
            double x = numberValue(position.get("x"));
            double y = numberValue(position.get("y"));
            double width = numberValue(style.get("width"), 270);
            geometry.add(new double[]{x, y, width});
            labels.add(textValue(data.get("title"), "节点 " + (labels.size() + 1)));
            tones.add(textValue(data.get("tone"), "blue"));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + width);
            maxY = Math.max(maxY, y);
        }

        double widthSpan = Math.max(1, maxX - minX);
        double heightSpan = Math.max(1, maxY - minY);
        double scale = Math.min(1, Math.min(416 / widthSpan, 200 / heightSpan));
        for (int index = 0; index < graphNodes.size(); index += 1) {
            double[] bounds = geometry.get(index);
            Map<String, Object> thumbnailNode = new LinkedHashMap<>();
            thumbnailNode.put("x", rounded(12 + (bounds[0] - minX) * scale));
            thumbnailNode.put("y", rounded(10 + (bounds[1] - minY) * scale));
            thumbnailNode.put("w", rounded(Math.max(44, bounds[2] * scale)));
            thumbnailNode.put("label", labels.get(index));
            thumbnailNode.put("tone", tones.get(index));
            topologyNodes.add(thumbnailNode);
            nodeIndexes.put(textValue(graphNodes.get(index).get("id"), String.valueOf(index)), index);
        }

        List<List<Integer>> topologyEdges = new ArrayList<>();
        for (Map<String, Object> edge : graphEdges) {
            Integer source = nodeIndexes.get(textValue(edge.get("source"), null));
            Integer target = nodeIndexes.get(textValue(edge.get("target"), null));
            if (source != null && target != null) {
                topologyEdges.add(List.of(source, target));
            }
        }

        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("nodes", topologyNodes);
        topology.put("edges", topologyEdges);
        return topology;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Map<String, Object>> mapValues(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        return source.stream().map(WorkflowService::mapValue).toList();
    }

    private static double numberValue(Object value) {
        return numberValue(value, 0);
    }

    private static double numberValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String textValue(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static double rounded(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private String modelLabel(Long modelId) {
        if (modelId == null) {
            return null;
        }
        AiModel model = aiModelMapper.selectById(modelId);
        return model == null ? null : model.getName();
    }

    /**
     * 卡片要展示的模型：沿「终端节点 -> 上游」回溯，收集输出链路上真正使用的生成模型，按上游 -> 下游排序。
     * 与 {@link #modelLabel} 语义不同：modelLabel 是保存时工具条选中的运行模型（只是兜底参数），
     * 图里节点换了模型它不会变，所以不能拿来当卡片文案。
     */
    private List<String> outputModels(Map<String, Object> graph) {
        Map<String, Object> root = mapValue(graph);
        List<Map<String, Object>> graphNodes = mapValues(root.get("nodes"));
        if (graphNodes.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        List<String> nodeOrder = new ArrayList<>();
        for (int index = 0; index < graphNodes.size(); index += 1) {
            String id = textValue(graphNodes.get(index).get("id"), String.valueOf(index));
            if (nodesById.putIfAbsent(id, graphNodes.get(index)) == null) {
                nodeOrder.add(id);
            }
        }
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        nodeOrder.forEach(id -> {
            incoming.put(id, new ArrayList<>());
            outgoing.put(id, new ArrayList<>());
        });
        for (Map<String, Object> edge : mapValues(root.get("edges"))) {
            String source = textValue(edge.get("source"), null);
            String target = textValue(edge.get("target"), null);
            if (source == null || target == null || !nodesById.containsKey(source) || !nodesById.containsKey(target)) {
                continue;
            }
            outgoing.get(source).add(target);
            incoming.get(target).add(source);
        }

        // 终端 = 没有下游的节点；未连线的独立节点同样算（它自身就是产物）
        List<String> terminals = new ArrayList<>();
        for (String id : nodeOrder) {
            if (outgoing.get(id).isEmpty()) {
                terminals.add(id);
            }
        }
        // 有剪辑 / 保存类输出节点时只从它们回溯：游离的音频节点也是"终端"，
        // 但它不是成片，拿它当末端会把卡片写成配音模型而不是生成模型
        List<String> outputTerminals = terminals.stream()
                .filter(id -> isOutputTerminal(nodesById.get(id)))
                .toList();
        if (!outputTerminals.isEmpty()) {
            terminals = outputTerminals;
        }
        Map<String, Integer> depth = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String id : terminals) {
            depth.put(id, 0);
            queue.add(id);
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            int upstreamDepth = depth.get(id) + 1;
            for (String upstream : incoming.get(id)) {
                // 访问过即跳过：既防环，也保证深度取最短路径
                if (depth.containsKey(upstream)) {
                    continue;
                }
                depth.put(upstream, upstreamDepth);
                queue.add(upstream);
            }
        }

        Map<String, Integer> canvasOrder = new LinkedHashMap<>();
        for (int index = 0; index < nodeOrder.size(); index += 1) {
            canvasOrder.put(nodeOrder.get(index), index);
        }
        List<String> reachable = new ArrayList<>(depth.keySet());
        // 深度大 = 更靠上游，先输出；同深度按画布原始顺序，保证多次请求结果稳定
        reachable.sort((left, right) -> {
            int byDepth = Integer.compare(depth.get(right), depth.get(left));
            return byDepth != 0 ? byDepth : Integer.compare(canvasOrder.getOrDefault(left, 0), canvasOrder.getOrDefault(right, 0));
        });

        Set<String> models = new LinkedHashSet<>();
        Map<String, String> modelOfNode = new LinkedHashMap<>();
        for (String id : reachable) {
            Map<String, Object> node = nodesById.get(id);
            // 已停用的节点不参与运行，卡片也不该把它用的模型算进来
            if (Boolean.FALSE.equals(mapValue(node.get("data")).get("enabled"))) {
                continue;
            }
            String model = generateModelOf(node);
            if (model == null) {
                continue;
            }
            modelOfNode.put(id, model);
            if (models.add(model) && models.size() >= MAX_OUTPUT_MODELS) {
                break;
            }
        }
        // 旁支生成节点（典型：给视频配音的音频节点挂在视频节点下游）不在输出链上，
        // 但它确实参与这个工作流的产物，模型应插在它锚点（所服务节点）模型之后
        for (String id : nodeOrder) {
            if (depth.containsKey(id) || models.size() >= MAX_OUTPUT_MODELS) {
                continue;
            }
            Map<String, Object> node = nodesById.get(id);
            if (Boolean.FALSE.equals(mapValue(node.get("data")).get("enabled"))) {
                continue;
            }
            String model = generateModelOf(node);
            if (model == null) {
                continue;
            }
            String anchorModel = null;
            for (String upstream : incoming.get(id)) {
                String candidate = modelOfNode.get(upstream);
                if (candidate != null) {
                    anchorModel = candidate;
                    break;
                }
            }
            List<String> merged = new ArrayList<>(models);
            int insertAt = anchorModel == null ? merged.size() : Math.max(merged.indexOf(anchorModel) + 1, 0);
            merged.add(insertAt, model);
            models = new LinkedHashSet<>(merged);
        }
        return List.copyOf(models);
    }

    /** 成片类终端节点：剪辑 / 保存 / 合成。它们存在时，卡片末端模型取这些节点的产物模型。 */
    private static boolean isOutputTerminal(Map<String, Object> node) {
        Map<String, Object> data = mapValue(node.get("data"));
        String kind = textValue(data.get("kind"), "");
        return kind.contains("Editing") || kind.startsWith("Save") || "CreateVideo".equals(kind);
    }

    private static String generateModelOf(Map<String, Object> node) {
        Map<String, Object> data = mapValue(node.get("data"));
        Map<String, Object> media = mapValue(data.get("media"));
        String mediaModel = textValue(media.get("model"), null);
        if (mediaModel != null) {
            return mediaModel;
        }
        String speechModel = textValue(media.get("speechModel"), null);
        if (speechModel != null) {
            return speechModel;
        }
        // ComfyUI 导入图没有 media，模型名落在 widgets_values 归一化后的 fields 里
        for (Map<String, Object> field : mapValues(data.get("fields"))) {
            if (!MODEL_FIELD_LABELS.contains(textValue(field.get("label"), ""))) {
                continue;
            }
            String value = textValue(field.get("value"), null);
            if (value != null) {
                return value.replaceFirst("(?i)\\.(safetensors|ckpt|pt|pth|bin|gguf)$", "");
            }
        }
        return null;
    }

    /** 内置官方示例已下线，列表只返回当前用户自己的工作流。 */
    private LambdaQueryWrapper<Workflow> visible(Long userId) {
        return new LambdaQueryWrapper<Workflow>().eq(Workflow::getUserId, userId);
    }

    private WorkflowResp toResp(Workflow workflow, boolean includeGraph) {
        WorkflowResp resp = new WorkflowResp();
        resp.setId(workflow.getId());
        resp.setTitle(workflow.getTitle());
        resp.setDescription(workflow.getDescription());
        resp.setType(workflow.getType());
        resp.setModelId(workflow.getModelId());
        resp.setModelLabel(workflow.getModelLabel());
        resp.setOutputModels(outputModels(workflow.getGraph()));
        resp.setOrigin(workflow.getOrigin());
        resp.setDemoKey(workflow.getDemoKey());
        resp.setSourcePath(workflow.getSourcePath());
        resp.setBuiltin(Boolean.TRUE.equals(workflow.getBuiltin()));
        resp.setNodeCount(workflow.getNodeCount());
        resp.setCreatedAt(workflow.getCreatedAt());
        resp.setUpdatedAt(workflow.getUpdatedAt());
        resp.setTemplateCode(workflow.getTemplateCode());
        resp.setCategory(workflow.getCategory());
        resp.setTags(workflow.getTags());
        resp.setCoverPath(workflow.getCoverPath());
        resp.setVersion(workflow.getVersion());
        resp.setModelAdapter(workflow.getModelAdapter());
        resp.setTopology(workflow.getTopology() != null
                ? workflow.getTopology()
                : buildTopology(workflow.getGraph()));
        resp.setDefaultParams(workflow.getDefaultParams());
        if (includeGraph) {
            resp.setGraph(workflow.getGraph());
        }
        return resp;
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
