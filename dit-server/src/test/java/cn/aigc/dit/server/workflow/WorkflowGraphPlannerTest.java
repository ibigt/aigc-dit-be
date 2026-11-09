package cn.aigc.dit.server.workflow;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** 需求 4.6.4.2：整图运行时单个节点缺提示词不牵连整次运行，跳过该节点及其下游并点名原因。 */
    @Test
    void lenientWholeRunSkipsBlankPromptNodeAndItsDownstream() {
        Map<String, Object> graph = graph(
                List.of(generator("ok", "ImageGenerator", "正常"),
                        generator("bad", "ImageGenerator", ""),
                        generator("down", "VideoGenerator", "下游有自己的提示词")),
                List.of(edge("bad", "down")));

        List<WorkflowGraphPlanner.Step> steps = WorkflowGraphPlanner.planForRun(graph, null);

        // 三个步骤都在计划里（跳过的也要进 run，前端才能点名展示），bad/down 带 skipReason
        assertEquals(3, steps.size());
        var byId = steps.stream().collect(java.util.stream.Collectors.toMap(WorkflowGraphPlanner.Step::nodeId, s -> s));
        assertNull(byId.get("ok").skipReason());
        assertEquals("缺少提示词", byId.get("bad").skipReason());
        // 下游连带跳过：依赖被跳过上游的产物，即便自身有提示词
        assertTrue(byId.get("down").skipReason() != null && byId.get("down").skipReason().contains("未运行"));
    }

    /** 需求 4.6.4.1：单节点运行目标节点不可运行时仍直接拦截（strict 抛错）。 */
    @Test
    void lenientSingleNodeRunStillInterceptsBlankPrompt() {
        Map<String, Object> graph = graph(
                List.of(generator("bad", "ImageGenerator", "")), List.of());
        BizException error = assertThrows(BizException.class,
                () -> WorkflowGraphPlanner.planForRun(graph, "bad"));
        assertTrue(error.getMessage().contains("提示词"));
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
    void sameSourceMultipleSegmentsAreAllKeptWhenClipIdPresent() {
        // 同一上游节点的同一输出被拆成两段（不同裁剪区间）：
        // 有 clipId 时两段都保留；按 (sourceNodeId, outputIndex) 去重会把它们合成一条，
        // 导致导出成片只剩用户「选中」的那一段。
        var steps = WorkflowGraphPlanner.plan(graph(
                List.of(
                        generator("a", "VideoGenerator", "a"),
                        editing("edit", List.of(
                                clip("c1", "a", 0, 0, 0.0, 5.0),
                                clip("c2", "a", 0, 1, 5.0, 10.0)))),
                List.of(edge("a", "edit"))));
        var clips = steps.get(1).clips();
        assertEquals(2, clips.size(), "同源同输出的两段都要保留");
        assertEquals(List.of(0.0, 5.0), clips.stream().map(WorkflowGraphPlanner.Clip::trimStart).toList());
        assertEquals(List.of(5.0, 10.0), clips.stream().map(WorkflowGraphPlanner.Clip::trimEnd).toList());
    }

    @Test
    void legacyClipsWithoutClipIdStillDedupeBySourceAndOutput() {
        // 旧草稿没有 clipId：保持原有去重口径（同源同输出只保留一条），不改变历史行为
        var steps = WorkflowGraphPlanner.plan(graph(
                List.of(
                        generator("a", "VideoGenerator", "a"),
                        editing("edit", List.of(
                                clip(null, "a", 0, 0, 0.0, 5.0),
                                clip(null, "a", 0, 1, 5.0, 10.0)))),
                List.of(edge("a", "edit"))));
        assertEquals(1, steps.get(1).clips().size());
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

    /**
     * 功能测试画布：一张图分流到两个视频节点，再汇入同一个剪辑节点。
     * 剪辑节点未显式配置 clips 时应自动收下全部上游视频，且顺序按拓扑（vid-1 先、vid-2 后）。
     */
    @Test
    void singleImageFeedsTwoVideosIntoOneEditingNode() {
        Map<String, Object> graph = graph(
                List.of(
                        generator("img-1", "ImageGenerator", "a cat"),
                        generator("vid-1", "VideoGenerator", "cat walks"),
                        generator("vid-2", "VideoGenerator", "cat jumps"),
                        editing("edit-1", List.of())),
                List.of(
                        edge("img-1", "vid-1"),
                        edge("img-1", "vid-2"),
                        edge("vid-1", "edit-1"),
                        edge("vid-2", "edit-1")));

        List<WorkflowGraphPlanner.Step> steps = WorkflowGraphPlanner.plan(graph);

        assertEquals(List.of("img-1", "vid-1", "vid-2", "edit-1"),
                steps.stream().map(WorkflowGraphPlanner.Step::nodeId).toList());
        assertEquals(List.of("GENERATE", "GENERATE", "GENERATE", "EDIT"),
                steps.stream().map(WorkflowGraphPlanner.Step::stepType).toList());

        WorkflowGraphPlanner.Step edit = steps.get(3);
        assertEquals(List.of("vid-1", "vid-2"),
                edit.clips().stream().map(WorkflowGraphPlanner.Clip::sourceNodeId).toList());
        assertEquals(List.of(0, 1), edit.clips().stream().map(WorkflowGraphPlanner.Clip::order).toList());
    }

    @Test
    void localImportedClipCanBePlannedWithoutUpstreamVideoGenerator() {
        Map<String, Object> graph = graph(
                List.of(editing("edit", List.of(Map.of(
                        "sourceNodeId", "local:42", "outputIndex", 0, "order", 0,
                        "trimStart", 0.5, "trimEnd", 4.5, "muted", false, "volume", 0.8,
                        "enabled", true, "localKey", "users/7/assets/local.mp4")))),
                List.of());

        var steps = WorkflowGraphPlanner.plan(graph);

        assertEquals(1, steps.size());
        assertEquals("EDIT", steps.get(0).stepType());
        assertEquals(1, steps.get(0).clips().size());
        assertEquals("users/7/assets/local.mp4", steps.get(0).clips().get(0).localKey());
        assertEquals("local:42", steps.get(0).clips().get(0).sourceNodeId());
        assertEquals(0.5, steps.get(0).clips().get(0).trimStart());
        assertEquals(4.5, steps.get(0).clips().get(0).trimEnd());
    }

    @Test
    void localImportDisablesAutomaticUpstreamVideoBackfill() {
        // 用户显式编排了导入素材时，不在时间线上的上游视频不应被自动补进成片
        Map<String, Object> graph = graph(
                List.of(generator("clip", "VideoGenerator", "奔跑"),
                        editing("edit", List.of(Map.of(
                                "sourceNodeId", "local:1", "outputIndex", 0, "order", 0,
                                "trimStart", 0.0, "trimEnd", 3.0, "enabled", true,
                                "localKey", "users/7/assets/i.mp4")))),
                List.of(edge("clip", "edit")));

        var steps = WorkflowGraphPlanner.plan(graph);
        var edit = steps.stream().filter(item -> "EDIT".equals(item.stepType())).findFirst().orElseThrow();

        assertEquals(1, edit.clips().size());
        assertEquals("users/7/assets/i.mp4", edit.clips().get(0).localKey());
        assertTrue(edit.clips().stream().noneMatch(item -> "clip".equals(item.sourceNodeId())));
    }

    @Test
    void editWithoutUpstreamVideoOrLocalImportStillRejected() {
        Map<String, Object> graph = graph(
                List.of(editing("edit", List.of(Map.of("sourceNodeId", "ghost", "order", 0,
                        "trimStart", 0.0, "trimEnd", 3.0, "enabled", true)))),
                List.of());
        BizException error = assertThrows(BizException.class, () -> WorkflowGraphPlanner.plan(graph));
        assertTrue(error.getMessage().contains("缺少上游视频生成节点"));
    }

    @Test
    void localImportedAudioClipKeepsLocalKeyAndStart() {
        Map<String, Object> editNode = Map.of("id", "edit", "data", Map.of(
                "kind", "VideoEditing", "title", "edit", "enabled", true,
                "editing", Map.of(
                        "clips", List.of(Map.of("sourceNodeId", "local:1", "outputIndex", 0, "order", 0,
                                "trimStart", 0.0, "trimEnd", 3.0, "enabled", true,
                                "localKey", "users/7/assets/i.mp4")),
                        "audioClips", List.of(Map.of("sourceNodeId", "local:9", "outputIndex", 0,
                                "start", 2.5, "trimStart", 0.0, "trimEnd", 4.0,
                                "muted", false, "volume", 1.0,
                                "localKey", "users/7/assets/voice.mp3")))));
        var steps = WorkflowGraphPlanner.plan(graph(List.of(editNode), List.of()));

        assertEquals(1, steps.get(0).audioClips().size());
        assertEquals("users/7/assets/voice.mp3", steps.get(0).audioClips().get(0).localKey());
        assertEquals(2.5, steps.get(0).audioClips().get(0).start());
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

    /** 剪辑片段配置：clipId 可为 null（模拟旧草稿没有该字段）。 */
    private static Map<String, Object> clip(String clipId, String sourceId, int outputIndex,
                                            int order, double trimStart, double trimEnd) {
        Map<String, Object> clip = new java.util.LinkedHashMap<>();
        if (clipId != null) {
            clip.put("clipId", clipId);
        }
        clip.put("sourceNodeId", sourceId);
        clip.put("outputIndex", outputIndex);
        clip.put("order", order);
        clip.put("trimStart", trimStart);
        clip.put("trimEnd", trimEnd);
        return clip;
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
