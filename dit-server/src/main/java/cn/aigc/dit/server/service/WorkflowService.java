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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final Set<String> TYPES = Set.of("image", "video", "audio", "import", "template");

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
        wrapper.orderByDesc(Workflow::getBuiltin).orderByDesc(Workflow::getUpdatedAt);
        return workflowMapper.selectList(wrapper).stream().map(item -> toResp(item, false)).toList();
    }

    public WorkflowResp getMine(Long id) {
        return toResp(requireReadable(id), true);
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
        boolean builtin = Boolean.TRUE.equals(workflow.getBuiltin());
        if (!builtin && !UserContext.requireUserId().equals(workflow.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        return workflow;
    }

    private Workflow requireOwned(Long id) {
        Workflow workflow = workflowMapper.selectById(id);
        if (workflow == null || Boolean.TRUE.equals(workflow.getBuiltin())
                || !UserContext.requireUserId().equals(workflow.getUserId())) {
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
    }

    private String modelLabel(Long modelId) {
        if (modelId == null) {
            return null;
        }
        AiModel model = aiModelMapper.selectById(modelId);
        return model == null ? null : model.getName();
    }

    private LambdaQueryWrapper<Workflow> visible(Long userId) {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(nested -> nested.eq(Workflow::getUserId, userId).or().eq(Workflow::getBuiltin, true));
        return wrapper;
    }

    private WorkflowResp toResp(Workflow workflow, boolean includeGraph) {
        WorkflowResp resp = new WorkflowResp();
        resp.setId(workflow.getId());
        resp.setTitle(workflow.getTitle());
        resp.setDescription(workflow.getDescription());
        resp.setType(workflow.getType());
        resp.setModelId(workflow.getModelId());
        resp.setModelLabel(workflow.getModelLabel());
        resp.setOrigin(workflow.getOrigin());
        resp.setDemoKey(workflow.getDemoKey());
        resp.setSourcePath(workflow.getSourcePath());
        resp.setBuiltin(Boolean.TRUE.equals(workflow.getBuiltin()));
        resp.setNodeCount(workflow.getNodeCount());
        resp.setCreatedAt(workflow.getCreatedAt());
        resp.setUpdatedAt(workflow.getUpdatedAt());
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
