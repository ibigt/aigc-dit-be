package cn.aigc.dit.server.workflow;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowGraphPlannerTest {

    @Test
    void ordersTextAndGeneratorsAndSkipsComfyNodes() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        note("brief", "雨夜竞速"),
                        generator("shot", "ImageGenerator", ""),
                        generator("clip", "VideoGenerator", "镜头推进"),
                        Map.of("id", "sampler", "data", Map.of("kind", "KSampler", "enabled", true)),
                        generator("muted", "AudioGenerator", "旁白")),
                "edges", List.of(
                        edge("brief", "shot"),
                        edge("shot", "clip"),
                        edge("sampler", "clip")));

        List<WorkflowGraphPlanner.Step> steps = WorkflowGraphPlanner.plan(graph);

        assertEquals(List.of("brief", "muted", "shot", "clip"), steps.stream().map(WorkflowGraphPlanner.Step::nodeId).toList());
        assertEquals(List.of("TEXT", "GENERATE", "GENERATE", "GENERATE"),
                steps.stream().map(WorkflowGraphPlanner.Step::stepType).toList());
        assertEquals("雨夜竞速", steps.get(2).resolvedPrompt());
        assertEquals(List.of("brief"), steps.get(2).dependsOn());
        assertEquals(List.of("shot"), steps.get(3).dependsOn());
    }

    @Test
    void singleNodeIncludesUpstreamOnly() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        generator("a", "ImageGenerator", "甲"),
                        generator("b", "ImageGenerator", "乙"),
                        generator("c", "ImageGenerator", "丙")),
                "edges", List.of(edge("a", "b"), edge("b", "c")));

        List<WorkflowGraphPlanner.Step> steps = WorkflowGraphPlanner.plan(graph, "b");

        assertEquals(List.of("a", "b"), steps.stream().map(WorkflowGraphPlanner.Step::nodeId).toList());
    }

    @Test
    void danglingEdgesCannotResolveVariablesThroughDeletedBridge() {
        BizException error = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph(
                List.of(text("a", "A", Map.of("x", "A")), generator("video", "VideoGenerator", "{{x}}")),
                List.of(edge("a", "deleted"), edge("deleted", "video")))));
        assertTrue(error.getMessage().contains("x"));
        assertTrue(error.getMessage().contains("缺少变量"));
    }

    @Test
    void rejectsCycleAndBlankPrompt() {
        Map<String, Object> cycle = Map.of(
                "nodes", List.of(generator("a", "ImageGenerator", "甲"), generator("b", "ImageGenerator", "乙")),
                "edges", List.of(edge("a", "b"), edge("b", "a")));
        BizException cycleError = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(cycle));
        assertTrue(cycleError.getMessage().contains("循环"));

        Map<String, Object> blank = Map.of(
                "nodes", List.of(generator("a", "ImageGenerator", "  ")),
                "edges", List.of());
        BizException blankError = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(blank));
        assertTrue(blankError.getMessage().contains("提示词"));
    }

    @Test
    void plansTextGeneratorAndTerminalEditInOrder() {
        Map<String, Object> graph = graph(
                List.of(text("brief", "{{角色}}奔跑", Map.of("角色", "阿青")),
                        generator("clip", "VideoGenerator", "{{角色}}奔跑"),
                        editing("edit", List.of(Map.of("sourceNodeId", "clip", "outputIndex", 2,
                                "order", 0, "trimStart", 1.5, "trimEnd", 4.5,
                                "muted", true, "volume", 0.6)))),
                List.of(edge("brief", "clip"), edge("clip", "edit")));

        var steps = WorkflowGraphPlanner.plan(graph);
        assertEquals(List.of("TEXT", "GENERATE", "EDIT"), steps.stream().map(WorkflowGraphPlanner.Step::stepType).toList());
        assertEquals("阿青奔跑", steps.get(0).outputs().get(0).value());
        assertEquals("阿青奔跑", steps.get(1).resolvedPrompt());
        assertEquals("{{角色}}奔跑", steps.get(1).prompt());
        assertEquals(List.of("clip"), steps.get(2).dependsOn());
        assertEquals(2, steps.get(2).clips().get(0).outputIndex());
        assertEquals(1.5, steps.get(2).clips().get(0).trimStart());
        assertEquals(4.5, steps.get(2).clips().get(0).trimEnd());
        assertTrue(steps.get(2).clips().get(0).muted());
        assertEquals(0.6, steps.get(2).clips().get(0).volume());
    }

    @Test
    void localVariablesOverrideConnectedAndOtherReachableText() {
        Map<String, Object> graph = graph(
                List.of(text("background", "角色={{角色}}", Map.of("角色", "路人", "地点", "雨巷")),
                        text("brief", "{{角色}}在{{地点}}", Map.of("角色", "阿青")),
                        generator("clip", "VideoGenerator", "{{角色}}在{{地点}}",
                                Map.of("variables", Map.of("角色", "小陈")))),
                List.of(edge("background", "brief"), edge("brief", "clip")));

        var steps = WorkflowGraphPlanner.plan(graph);
        assertEquals("小陈在雨巷", steps.get(2).resolvedPrompt());
    }

    @Test
    void resolvesTableAndNamedTextOutputsTopologicallyIncludingNestedVariables() {
        var table = Map.<String, Object>of("id", "table", "data", Map.of("kind", "DataTable",
                "fields", List.of(Map.of("label", "place", "value", "rainy lane"))));
        var first = Map.<String, Object>of("id", "first", "data", Map.of("kind", "MarkdownNote",
                "variables", Map.of("who", "Ada", "nested", "{{who}}"),
                "fields", List.of(Map.of("label", "scene", "value", "{{nested}} in {{place}}"))));
        var second = Map.<String, Object>of("id", "second", "data", Map.of("kind", "MarkdownNote",
                "fields", List.of(Map.of("label", "shot", "value", "Film {{scene}}"))));
        var steps = WorkflowGraphPlanner.plan(graph(List.of(generator("video", "VideoGenerator", "{{shot}}"),
                second, first, table), List.of(edge("table", "first"), edge("first", "second"), edge("second", "video"))));
        assertEquals("Film Ada in rainy lane", steps.get(3).resolvedPrompt());
        assertEquals("{{shot}}", steps.get(3).prompt());
        assertEquals("Ada in rainy lane", steps.get(1).outputs().get(0).value());
    }

    @Test
    void tableFieldsFeedGeneratorsAndVariableErrorsIdentifyNode() {
        var table = Map.<String, Object>of("id", "table", "data", Map.of("kind", "DataTable",
                "fields", List.of(Map.of("label", "place", "value", "lane"))));
        assertEquals("lane", WorkflowGraphPlanner.plan(graph(List.of(table,
                generator("video", "VideoGenerator", "{{place}}")), List.of(edge("table", "video")))).get(1).resolvedPrompt());
        BizException missing = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph(
                List.of(generator("video", "VideoGenerator", "{{missing}}")), List.of())));
        assertTrue(missing.getMessage().contains("video"));
        var conflicting = Map.<String, Object>of("id", "other", "data", Map.of("kind", "DataTable",
                "fields", List.of(Map.of("label", "place", "value", "beach"))));
        BizException conflict = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph(
                List.of(table, conflicting, generator("video", "VideoGenerator", "{{place}}")),
                List.of(edge("table", "video"), edge("other", "video")))));
        assertTrue(conflict.getMessage().contains("video") && conflict.getMessage().contains("冲突"));
    }

    @Test
    void variablePriorityUsesRealShortestEdgeDistanceThroughTransparentNodes() {
        var a = text("a", "A", Map.of("x", "A"));
        var b = text("b", "B", Map.of("x", "B"));
        var bridge = Map.<String, Object>of("id", "bridge", "data", Map.of("kind", "Reroute"));
        var near = Map.<String, Object>of("id", "near", "data", Map.of("kind", "Reroute"));
        var far = Map.<String, Object>of("id", "far", "data", Map.of("kind", "Reroute"));
        var nodes = List.of(a, b, bridge, near, far, generator("video", "VideoGenerator", "{{x}}"));
        for (var edges : List.of(
                List.of(edge("b", "bridge"), edge("bridge", "video"), edge("a", "video")),
                List.of(edge("b", "far"), edge("far", "bridge"), edge("bridge", "video"), edge("a", "near"), edge("near", "video")))) {
            var steps = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> WorkflowGraphPlanner.plan(graph(nodes, edges)));
            assertEquals("A", steps.get(steps.size() - 1).resolvedPrompt());
        }
        assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph(nodes,
                List.of(edge("a", "near"), edge("near", "video"), edge("b", "bridge"), edge("bridge", "video")))));
    }

    @Test
    void preservesSelectedUpstreamOutputIndex() {
        Map<String, Object> graph = graph(
                List.of(generator("source", "ImageGenerator", "图像"),
                        generator("target", "VideoGenerator", "视频", Map.of("outputIndex", 3))),
                List.of(edge("source", "target")));

        var steps = WorkflowGraphPlanner.plan(graph);
        assertEquals(3, steps.get(1).outputIndex());
    }

    @Test
    void musicUsesItsSelectedModelWithoutChangingSpeechSelection() {
        var steps = WorkflowGraphPlanner.plan(graph(List.of(
                generator("music", "AudioGenerator", "jazz", Map.of("tab", "music", "musicModel", "Mock Music")),
                generator("speech", "AudioGenerator", "hello", Map.of("tab", "speech", "musicModel", "Mock Music"))), List.of()));
        assertEquals("Mock Music", steps.get(0).modelName());
        assertEquals("Minimax-H3", steps.get(1).modelName());
    }

    @Test
    void nonExecutableUntypedBridgeKeepsReachableFilmSources() {
        var steps = WorkflowGraphPlanner.plan(graph(List.of(
                generator("a", "VideoGenerator", "a"),
                Map.of("id", "bridge", "data", Map.of()), editing("edit", List.of())),
                List.of(edge("a", "bridge"), edge("bridge", "edit"))));
        assertEquals(List.of("a", "edit"), steps.stream().map(WorkflowGraphPlanner.Step::nodeId).toList());
        assertEquals("a", steps.get(1).clips().get(0).sourceNodeId());
    }

    @Test
    void defaultFilmQueueUsesEdgeOrderedAncestorsAndOneSelectedCopy() {
        var steps = WorkflowGraphPlanner.plan(graph(List.of(
                generator("a", "VideoGenerator", "a", Map.of("copies", 3)),
                generator("b", "VideoGenerator", "b", Map.of("copies", 2, "selectedOutputIndex", 1)),
                text("bridge", "brief", Map.of()), editing("edit", List.of())),
                List.of(edge("b", "bridge"), edge("a", "bridge"), edge("bridge", "edit"))));
        var clips = steps.get(3).clips();
        assertEquals(List.of("b", "a"), clips.stream().map(WorkflowGraphPlanner.Clip::sourceNodeId).toList());
        assertEquals(List.of(1, 0), clips.stream().map(WorkflowGraphPlanner.Clip::outputIndex).toList());
    }

    @Test
    void directReferenceAssetsJoinRefsAfterEdgeStatics() {
        // 旧画布的素材节点连线引用在前，media.referenceAssets 直接附件在后，无效 key（无斜杠）丢弃
        var assetNode = Map.<String, Object>of("id", "ref", "data", Map.of(
                "kind", "ReferenceAsset", "assetType", "IMAGE", "assetKey", "users/1/inputs/a.png"));
        var target = generator("shot", "VideoGenerator", "视频", Map.of("referenceAssets", List.of(
                Map.of("storageKey", "users/1/assets/b.mp4", "type", "VIDEO"),
                Map.of("storageKey", "racer-first", "type", "IMAGE"),
                Map.of("storageKey", "users/1/assets/c.png", "type", "IMAGE"))));
        // 素材节点不是可执行步骤，计划里只有目标生成节点
        var steps = WorkflowGraphPlanner.plan(graph(List.of(assetNode, target), List.of(edge("ref", "shot"))));
        assertEquals(List.of("shot"), steps.stream().map(WorkflowGraphPlanner.Step::nodeId).toList());
        var refs = steps.get(0).refs();
        assertEquals(List.of("users/1/inputs/a.png", "users/1/assets/b.mp4", "users/1/assets/c.png"),
                refs.stream().map(WorkflowGraphPlanner.Ref::storageKey).toList());
        assertEquals(List.of("image", "video", "image"),
                refs.stream().map(WorkflowGraphPlanner.Ref::type).toList());
    }

    @Test
    void imageNodeFiltersDirectReferenceAssetsByAcceptedType() {
        var target = generator("shot", "ImageGenerator", "图像", Map.of("referenceAssets", List.of(
                Map.of("storageKey", "users/1/assets/v.mp4", "type", "video"),
                Map.of("storageKey", "users/1/assets/c.png", "type", "IMAGE"))));
        var steps = WorkflowGraphPlanner.plan(graph(List.of(target), List.of()));
        assertEquals(List.of("users/1/assets/c.png"),
                steps.get(0).refs().stream().map(WorkflowGraphPlanner.Ref::storageKey).toList());
    }

    @Test
    void rejectsReversedTrimInsteadOfSilentlyResettingIt() {
        BizException error = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph(
                List.of(generator("video", "VideoGenerator", "film"), editing("edit", List.of(
                        Map.of("sourceNodeId", "video", "trimStart", 4, "trimEnd", 2)))),
                List.of(edge("video", "edit")))));
        assertTrue(error.getMessage().contains("edit") && error.getMessage().contains("裁剪"));
    }

    @Test
    void editKeepsDifferentOutputsFromSameVideoNodeInPersistedOrder() {
        Map<String, Object> graph = graph(
                List.of(generator("video", "VideoGenerator", "视频"), editing("edit", List.of(
                        Map.of("sourceNodeId", "video", "outputIndex", 2, "order", 1),
                        Map.of("sourceNodeId", "video", "outputIndex", 0, "order", 0)))),
                List.of(edge("video", "edit")));

        var clips = WorkflowGraphPlanner.plan(graph).get(1).clips();
        assertEquals(2, clips.size());
        assertEquals(List.of(0, 2), clips.stream().map(WorkflowGraphPlanner.Clip::outputIndex).toList());
    }

    @Test
    void disabledClipsAreExcludedAndNotAutoReaddedToFilmQueue() {
        var steps = WorkflowGraphPlanner.plan(graph(List.of(
                generator("a", "VideoGenerator", "a"),
                generator("b", "VideoGenerator", "b"),
                editing("edit", List.of(
                        Map.of("sourceNodeId", "a", "outputIndex", 0, "order", 0, "enabled", false),
                        Map.of("sourceNodeId", "b", "outputIndex", 0, "order", 1)))),
                List.of(edge("a", "edit"), edge("b", "edit"))));

        var clips = steps.get(2).clips();
        assertEquals(List.of("b"), clips.stream().map(WorkflowGraphPlanner.Clip::sourceNodeId).toList());
    }

    @Test
    void allClipsDisabledRejectsWithUnderstandableError() {
        BizException error = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph(
                List.of(generator("video", "VideoGenerator", "film"), editing("edit", List.of(
                        Map.of("sourceNodeId", "video", "outputIndex", 0, "order", 0, "enabled", false)))),
                List.of(edge("video", "edit")))));
        assertTrue(error.getMessage().contains("禁用"));
    }

    @Test
    void musicStyleAndDurationFlowIntoAudioStep() {
        var steps = WorkflowGraphPlanner.plan(graph(List.of(
                generator("music", "AudioGenerator", "jazz", Map.of(
                        "tab", "music", "musicStyle", "爵士", "musicDuration", 45.0))),
                List.of()));
        // audio 参数存放在 step 的 audio 映射中（请求构造阶段读取）
        var audio = steps.get(0).audio();
        assertEquals("爵士", audio.get("musicStyle"));
        assertEquals(45.0, audio.get("musicDuration"));
    }

    @Test
    void rejectsGeneratorSelfEdgeAsCycle() {
        Map<String, Object> graph = graph(
                List.of(generator("video", "VideoGenerator", "视频")),
                List.of(edge("video", "video")));

        BizException error = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph));
        assertTrue(error.getMessage().contains("循环"));
    }

    @Test
    void selectedNodeResolvesOnlyItsDependencyClosure() {
        Map<String, Object> graph = graph(
                List.of(generator("selected", "ImageGenerator", "{{角色}}", Map.of("variables", Map.of("角色", "阿青"))),
                        generator("unrelated", "ImageGenerator", "{{天气}}")),
                List.of());

        var steps = WorkflowGraphPlanner.plan(graph, "selected");
        assertEquals(List.of("selected"), steps.stream().map(WorkflowGraphPlanner.Step::nodeId).toList());
        assertEquals("阿青", steps.get(0).resolvedPrompt());
    }

    @Test
    void editNodeCannotFeedDownstream() {
        Map<String, Object> graph = graph(
                List.of(generator("clip", "VideoGenerator", "视频"), editing("edit", List.of())),
                List.of(edge("clip", "edit"), edge("edit", "clip")));

        BizException error = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph));
        assertTrue(error.getMessage().contains("终端"));
    }

    @Test
    void editRequiresReachableVideoAndTextDoesNotConsumeGeneratorLimit() {
        BizException missingVideo = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph(
                List.of(note("brief", "说明"), editing("edit", List.of())), List.of(edge("brief", "edit")))));
        assertTrue(missingVideo.getMessage().contains("视频"));

        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        nodes.add(note("brief", "说明"));
        nodes.addAll(IntStream.range(0, 20).mapToObj(i -> generator("g" + i, "ImageGenerator", "图像" + i)).toList());
        assertEquals(21, WorkflowGraphPlanner.plan(graph(nodes, List.of())).size());
    }

    private static Map<String, Object> generator(String id, String kind, String prompt) {
        return generator(id, kind, prompt, Map.of());
    }

    private static Map<String, Object> generator(String id, String kind, String prompt, Map<String, Object> mediaExtra) {
        Map<String, Object> media = new java.util.LinkedHashMap<>(mediaExtra);
        media.put("prompt", prompt);
        media.put("model", "Minimax-H3");
        return Map.of("id", id, "data", Map.of(
                "kind", kind,
                "title", id,
                "enabled", true,
                "media", media));
    }

    private static Map<String, Object> text(String id, String value, Map<String, String> variables) {
        return Map.of("id", id, "data", Map.of(
                "kind", "MarkdownNote", "title", id, "enabled", true,
                "fields", List.of(Map.of("label", "内容", "value", value)),
                "variables", variables));
    }

    private static Map<String, Object> editing(String id, List<Map<String, Object>> clips) {
        return Map.of("id", id, "data", Map.of(
                "kind", "VideoEditing", "title", id, "enabled", true,
                "editing", Map.of("clips", clips)));
    }

    private static Map<String, Object> graph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return Map.of("nodes", nodes, "edges", edges);
    }

    private static Map<String, Object> note(String id, String value) {
        return Map.of("id", id, "data", Map.of(
                "kind", "MarkdownNote",
                "fields", List.of(Map.of("value", value))));
    }

    private static Map<String, Object> edge(String source, String target) {
        return Map.of("source", source, "target", target);
    }
}
