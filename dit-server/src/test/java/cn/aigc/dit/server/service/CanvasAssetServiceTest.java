package cn.aigc.dit.server.service;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasAssetServiceTest {

    private static Map<String, Object> node(String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "VideoGenerator");
        data.put("title", "视频 " + id);
        data.put("media", Map.of("prompt", "一只猫"));
        data.put("runtime", Map.of("status", "SUCCEEDED"));
        data.put("savedAsset", Map.of("assetId", 3));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "comfy");
        node.put("position", Map.of("x", 10, "y", 20));
        node.put("data", data);
        node.put("outputs", List.of(Map.of("storageKey", "users/1/outputs/a.mp4")));
        node.put("selected", true);
        return node;
    }

    private static Map<String, Object> graph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    private static Map<String, Object> edge(String id, String source, String target) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("target", target);
        edge.put("selected", true);
        return edge;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstNode(Map<String, Object> clean) {
        return (Map<String, Object>) ((List<?>) clean.get("nodes")).get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stripsRuntimeStateButKeepsNodeConfig() {
        Map<String, Object> clean = CanvasAssetService.sanitizeGraph(
                graph(List.of(node("n1")), List.of()));
        Map<String, Object> cleanNode = firstNode(clean);
        assertEquals("n1", cleanNode.get("id"));
        assertFalse(cleanNode.containsKey("outputs"), "节点级产物副本不进资产");
        assertFalse(cleanNode.containsKey("selected"));
        Map<String, Object> data = (Map<String, Object>) cleanNode.get("data");
        assertEquals("一只猫", ((Map<?, ?>) data.get("media")).get("prompt"));
        assertFalse(data.containsKey("runtime"));
        assertFalse(data.containsKey("savedAsset"));
        assertEquals(2, clean.size());
        assertTrue(((List<?>) clean.get("edges")).isEmpty());
    }

    @Test
    void dropsEdgesPointingOutsideTheAsset() {
        Map<String, Object> clean = CanvasAssetService.sanitizeGraph(graph(
                List.of(node("n1"), node("n2")),
                List.of(edge("e1", "n1", "n2"), edge("e2", "n1", "external"))));
        assertEquals(1, ((List<?>) clean.get("edges")).size());
    }

    @Test
    void acceptsMissingEdgesAsEmpty() {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of(node("n1")));
        assertEquals(0, ((List<?>) CanvasAssetService.sanitizeGraph(graph).get("edges")).size());
    }

    @Test
    void rejectsEmptyOversizedAndMalformedGraphs() {
        assertThrows(BizException.class, () -> CanvasAssetService.sanitizeGraph(null));
        assertThrows(BizException.class, () -> CanvasAssetService.sanitizeGraph(graph(List.of(), List.of())));
        assertThrows(BizException.class, () -> CanvasAssetService.sanitizeGraph(graph(List.of(node("n1"), node("n1")), List.of())));
        assertThrows(BizException.class, () -> CanvasAssetService.sanitizeGraph(graph(List.of(Map.of("id", "n1")), List.of())));
        Map<String, Object> noKind = node("n1");
        noKind.put("data", Map.of("media", Map.of("prompt", "猫")));
        assertThrows(BizException.class, () -> CanvasAssetService.sanitizeGraph(graph(List.of(noKind), List.of())));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= CanvasAssetService.MAX_NODES; i++) {
            tooMany.add(node("n" + i));
        }
        assertThrows(BizException.class, () -> CanvasAssetService.sanitizeGraph(graph(tooMany, List.of())));
    }

    @Test
    void rejectsPayloadLargerThanLimit() {
        Map<String, Object> fat = node("n1");
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) fat.get("data"));
        data.put("blob", "x".repeat(CanvasAssetService.MAX_GRAPH_BYTES + 1));
        fat.put("data", data);
        Map<String, Object> finalFat = fat;
        assertThrows(BizException.class,
                () -> CanvasAssetService.sanitizeGraph(graph(List.of(finalFat), List.of())));
    }
}
