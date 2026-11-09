package cn.aigc.dit.server.service;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.CanvasAsset;
import cn.aigc.dit.server.mapper.CanvasAssetMapper;
import cn.aigc.dit.server.model.req.CanvasAssetSaveReq;
import cn.aigc.dit.server.model.resp.CanvasAssetResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画布资产（V1C20 1.3）：保存节点配置快照，跨画布复用。
 *
 * <p>与素材库 {@code asset} 表刻意分开：素材库存的是媒体文件（mime/宽高/时长），
 * 画布资产存的是节点参数与内部连线。服务端落库前会独立清洗一遍，
 * 剥掉运行态与产物引用，避免把某个画布的 runtime.outputs / 私有 storageKey 带到别的画布。
 */
@Service
@RequiredArgsConstructor
public class CanvasAssetService extends ServiceImpl<CanvasAssetMapper, CanvasAsset> {

    static final int MAX_NODES = 20;
    static final int MAX_EDGES = 60;
    static final int MAX_GRAPH_BYTES = 512 * 1024;
    static final int MAX_LIST = 100;

    private static final Set<String> NODE_DROP_KEYS = Set.of(
            "outputs", "positionAbsolute", "width", "height", "parentId", "extent",
            "selected", "dragging", "resizing", "measured");
    private static final Set<String> DATA_DROP_KEYS = Set.of("runtime", "savedAsset", "icon");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<CanvasAssetResp> listMine() {
        Long userId = UserContext.requireUserId();
        List<CanvasAsset> assets = list(new LambdaQueryWrapper<CanvasAsset>()
                .eq(CanvasAsset::getUserId, userId)
                .orderByDesc(CanvasAsset::getCreatedAt)
                .last("LIMIT " + MAX_LIST));
        return assets.stream().map(asset -> toResp(asset, false)).toList();
    }

    public CanvasAssetResp getMine(Long id) {
        return toResp(requireOwned(id), true);
    }

    @Transactional
    public CanvasAssetResp create(CanvasAssetSaveReq req) {
        Long userId = UserContext.requireUserId();
        String name = req.getName() == null ? "" : req.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资产名称不能为空");
        }
        if (name.length() > 128) {
            name = name.substring(0, 128);
        }
        Map<String, Object> graph = sanitizeGraph(req.getGraph());
        CanvasAsset asset = new CanvasAsset();
        asset.setUserId(userId);
        asset.setName(name);
        asset.setNodeCount(sizeOf(graph.get("nodes")));
        asset.setNodeKind(nodeKindOf(graph.get("nodes")));
        asset.setGraph(graph);
        save(asset);
        return toResp(asset, false);
    }

    @Transactional
    public void deleteMine(Long id) {
        CanvasAsset asset = requireOwned(id);
        removeById(asset.getId());
    }

    private CanvasAsset requireOwned(Long id) {
        CanvasAsset asset = id == null ? null : getById(id);
        if (asset == null || !asset.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "画布资产不存在");
        }
        return asset;
    }

    private CanvasAssetResp toResp(CanvasAsset asset, boolean includeGraph) {
        CanvasAssetResp resp = new CanvasAssetResp();
        resp.setId(asset.getId());
        resp.setName(asset.getName());
        resp.setNodeKind(asset.getNodeKind());
        resp.setNodeCount(asset.getNodeCount());
        resp.setCreatedAt(asset.getCreatedAt());
        resp.setUpdatedAt(asset.getUpdatedAt());
        if (includeGraph) {
            resp.setGraph(asset.getGraph());
        }
        return resp;
    }

    private static int sizeOf(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static String nodeKindOf(Object rawNodes) {
        if (!(rawNodes instanceof List<?> nodes) || nodes.isEmpty()) {
            return "unknown";
        }
        if (nodes.size() > 1) {
            return "multi";
        }
        Object kind = dataOf(nodes.get(0)).get("kind");
        return kind == null ? "unknown" : String.valueOf(kind);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Object node) {
        Object data = node instanceof Map<?, ?> map ? map.get("data") : null;
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 结构与体积校验 + 运行态剥离。包级可见，便于不启 Spring 上下文直接单测。
     */
    static Map<String, Object> sanitizeGraph(Map<String, Object> graph) {
        Object rawNodes = graph == null ? null : graph.get("nodes");
        if (!(rawNodes instanceof List<?> nodes) || nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "画布资产需包含 1~" + MAX_NODES + " 个节点");
        }
        List<Object> cleanNodes = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?, ?> node)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "节点数据无效");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : node.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!NODE_DROP_KEYS.contains(key)) {
                    copy.put(key, entry.getValue());
                }
            }
            Object id = copy.get("id");
            if (!(id instanceof String nodeId) || nodeId.isBlank() || nodeId.length() > 128 || !ids.add(nodeId)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "节点 id 缺失或重复");
            }
            Object rawData = copy.get("data");
            if (!(rawData instanceof Map<?, ?> data)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "节点缺少配置数据");
            }
            Map<String, Object> cleanData = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : data.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!DATA_DROP_KEYS.contains(key)) {
                    cleanData.put(key, entry.getValue());
                }
            }
            if (!hasText(cleanData.get("kind")) && !hasText(cleanData.get("title"))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "节点缺少类型或标题");
            }
            copy.put("data", cleanData);
            cleanNodes.add(copy);
        }
        Object rawEdges = graph.get("edges");
        List<?> edges = rawEdges instanceof List<?> list ? list : List.of();
        if (edges.size() > MAX_EDGES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "连线数量超过 " + MAX_EDGES + " 条");
        }
        List<Object> cleanEdges = new ArrayList<>();
        for (Object raw : edges) {
            if (!(raw instanceof Map<?, ?> edge)) {
                continue;
            }
            Object source = edge.get("source");
            Object target = edge.get("target");
            if (!ids.contains(String.valueOf(source)) || !ids.contains(String.valueOf(target))) {
                continue; // 静态丢弃跨资产边界的连线，不把外部节点 id 带进来
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : edge.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!"selected".equals(key) && !"measured".equals(key)) {
                    copy.put(key, entry.getValue());
                }
            }
            cleanEdges.add(copy);
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("nodes", cleanNodes);
        clean.put("edges", cleanEdges);
        try {
            if (MAPPER.writeValueAsBytes(clean).length > MAX_GRAPH_BYTES) {
                throw new BizException(ErrorCode.BAD_REQUEST, "画布资产内容过大");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "画布资产内容无法序列化");
        }
        return clean;
    }

    private static boolean hasText(Object value) {
        return value instanceof String text && StringUtils.hasText(text);
    }
}
