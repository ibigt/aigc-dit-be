package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.ProjectMemoryService;
import cn.aigc.dit.server.drama.agent.impl.AssetExtractAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.AssetSupervisorImpl;
import cn.aigc.dit.server.drama.agent.impl.OutlineSkeletonAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.ScriptParseAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.ScriptSupervisorImpl;
import cn.aigc.dit.server.drama.agent.impl.SemanticNormalizationAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.SemanticNormalizationSupervisorImpl;
import cn.aigc.dit.server.drama.agent.impl.ShotDraftAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.ShotSupervisorImpl;
import cn.aigc.dit.server.drama.agent.impl.SkeletonSupervisorImpl;
import cn.aigc.dit.server.drama.mapper.DramaPlanDataMapper;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptPromptResolver;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import cn.aigc.dit.server.drama.text.SkeletonDoc;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.drama.text.TextChatRequest;
import cn.aigc.dit.server.drama.text.TextChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DramaAnalysisOrchestrator 端到端测试：4 步流水线 + 失败回滚。
 */
class DramaAnalysisOrchestratorTest {

    private final TextChatGateway gateway = mock(TextChatGateway.class);
    private final ScriptPromptResolver promptResolver = mock(ScriptPromptResolver.class);
    private final DramaPlanDataMapper planDataMapper = mock(DramaPlanDataMapper.class);
    private final AgentRunLogService runLog = mock(AgentRunLogService.class);
    private final ProjectMemoryService memoryService = mock(ProjectMemoryService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OutlineSkeletonAgentImpl skeletonAgent = new OutlineSkeletonAgentImpl(gateway, promptResolver, objectMapper);
    private final ScriptParseAgentImpl scriptAgent = new ScriptParseAgentImpl(gateway, promptResolver, objectMapper);
    private final SemanticNormalizationAgentImpl semanticAgent = new SemanticNormalizationAgentImpl(gateway, promptResolver, objectMapper);
    private final AssetExtractAgentImpl assetAgent = new AssetExtractAgentImpl(gateway, promptResolver, objectMapper);
    private final ShotDraftAgentImpl shotAgent = new ShotDraftAgentImpl(gateway, promptResolver, objectMapper);

    private final SkeletonSupervisorImpl skeletonSup = new SkeletonSupervisorImpl();
    private final ScriptSupervisorImpl scriptSup = new ScriptSupervisorImpl();
    private final SemanticNormalizationSupervisorImpl semanticSup = new SemanticNormalizationSupervisorImpl();
    private final AssetSupervisorImpl assetSup = new AssetSupervisorImpl();
    private final ShotSupervisorImpl shotSup = new ShotSupervisorImpl();

    private final DramaAnalysisOrchestrator orchestrator = new DramaAnalysisOrchestrator(
            skeletonAgent, skeletonSup,
            scriptAgent, scriptSup,
            semanticAgent, semanticSup,
            assetAgent, assetSup,
            shotAgent, shotSup,
            planDataMapper, runLog, memoryService, objectMapper);

    @Test
    void fullRun_allStepsSuccess() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        when(memoryService.resolveMemoryBlock(anyLong())).thenReturn("");

        // skeleton：返回合法 3 集骨架（满足 targetEpisodes=3 + SkeletonSupervisor 规则）
        String skeletonJson = """
                {"logline":"测试用骨架","style_prefix":"",
                 "episodes":[
                  {"index":1,"title":"第一集","logline":"开篇","hook":"钩子",
                   "characters":["洛白","林溪"],
                   "scenes_hint":[{"heading":"教室","purpose":"主场景"}],
                   "climax":"高潮","next_episode_tease":"下集"},
                  {"index":2,"title":"第二集","logline":"中段","hook":"钩子",
                   "characters":["洛白","林溪"],
                   "scenes_hint":[{"heading":"走廊","purpose":"主场景"}],
                   "climax":"高潮","next_episode_tease":"下集"},
                  {"index":3,"title":"第三集","logline":"结尾","hook":"钩子",
                   "characters":["洛白","林溪"],
                   "scenes_hint":[{"heading":"办公室","purpose":"主场景"}],
                   "climax":"高潮","next_episode_tease":"完结"}
                 ],
                 "characters_recurrent":["洛白","林溪"],
                 "world_rules":[]}
                """;
        // script：返回合法 ScriptDoc
        String scriptJson = """
                {"scenes":[{"index":1,"heading":"教室-日","characters":["洛白","林溪"],
                  "props":["古铜怀表"],
                  "dialogue":[{"speaker":"洛白","line":"我们走吧"}]}],"style_prefix":""}
                """;
        // assets：返回合法资产
        String assetsJson = """
                {"assets":[
                  {"name":"洛白","type":"角色","state_tag":"base","description":"男主","identity_invariants":"短","image_prompt":"","voice_lock":"","must_split":false},
                  {"name":"林溪","type":"角色","state_tag":"base","description":"女主","identity_invariants":"短","image_prompt":"","voice_lock":"","must_split":false},
                  {"name":"古铜怀表","type":"道具","state_tag":"base","description":"怀表","identity_invariants":"黄铜外壳，表盘有细划痕","image_prompt":"","voice_lock":"","must_split":false}
                ]}
                """;
        String semanticJson = """
                {"normalized_script":{"scenes":[{"index":1,"heading":"教室-日",
                  "characters":["洛白","林溪"],"props":["古铜怀表"],
                  "dialogue":[{"speaker":"洛白","line":"我们走吧"}]}],"style_prefix":""},
                 "entity_groups":[
                  {"entity_type":"角色","canonical_mention_id":"scene:0.characters:0","member_mention_ids":["scene:0.characters:0"],"confidence":0.9},
                  {"entity_type":"角色","canonical_mention_id":"scene:0.characters:1","member_mention_ids":["scene:0.characters:1"],"confidence":0.9},
                  {"entity_type":"道具","canonical_mention_id":"scene:0.props:0","member_mention_ids":["scene:0.props:0"],"confidence":0.9}],
                 "scene_lineage":[{"normalized_scene_index":1,"source_scene_indexes":[0],
                   "action_source_ids":[],"dialogue_source_ids":["scene:0.dialogue:0"]}]}
                """;
        // shots：返回合法分镜
        String shotsJson = """
                {"chapters":[{"index":1,"name":"开场","scene_index":1}],
                 "shots":[{"chapter_index":1,"index":1,"title":"开场","duration_sec":8,
                           "narrative_goal":"引子","scene":"教室","dialogue":"我们走吧",
                           "continuity_in":"","continuity_out":"",
                           "character_names":["洛白","林溪"],"prop_names":["古铜怀表"],
                           "location_name":"教室","cast_count":2}]}
                """;

        when(gateway.chat(anyLong(), any(TextChatRequest.class)))
                .thenReturn(TextChatResult.of(skeletonJson, "m", "stop", 100L, 100L, 0L))
                .thenReturn(TextChatResult.of(scriptJson, "m", "stop", 100L, 100L, 0L))
                .thenReturn(TextChatResult.of(scriptJson, "m", "stop", 100L, 100L, 0L))
                .thenReturn(TextChatResult.of(semanticJson, "m", "stop", 100L, 100L, 0L))
                .thenReturn(TextChatResult.of(assetsJson, "m", "stop", 100L, 100L, 0L))
                .thenReturn(TextChatResult.of(shotsJson, "m", "stop", 100L, 100L, 0L));

        DramaAnalysisRequest req = DramaAnalysisRequest.fullRun(
                1L, 100L, "sess-1", "原始文本", 3, "1", "m");
        DramaAnalysisResult result = orchestrator.analyze(req);

        assertTrue(result.allStepsOk(), "应当全流程成功，实际 failedAt=" + result.failedAt());
        assertEquals(List.of(DramaAnalysisResult.Step.values()), result.successfulSteps());
        assertEquals(4, result.successfulSteps().size());
        assertNotNull(result.skeleton());
        assertNotNull(result.script());
        assertNotNull(result.assets());
        assertNotNull(result.shots());
        org.mockito.Mockito.verify(runLog).record(eq("semantic_normalization"), any(), any());
        org.mockito.Mockito.verify(planDataMapper).upsert(eq(100L), eq("script_raw_agent"), anyString(), anyString(), any());
        org.mockito.Mockito.verify(planDataMapper).upsert(eq(100L), eq("script_normalized"), anyString(), anyString(), any());
    }

    @Test
    void skeletonFails_returnsAtSkeletonStep() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        when(memoryService.resolveMemoryBlock(anyLong())).thenReturn("");
        when(gateway.chat(anyLong(), any(TextChatRequest.class)))
                .thenReturn(TextChatResult.of("not json", "m", "stop", 100L, 100L, 0L));

        DramaAnalysisRequest req = DramaAnalysisRequest.fullRun(
                1L, 100L, "sess-1", "原始文本", 3, "1", "m");
        DramaAnalysisResult result = orchestrator.analyze(req);

        assertFalse(result.success());
        assertEquals(DramaAnalysisResult.Step.SKELETON, result.failedAt());
        assertNotNull(result.stepErrors().get(DramaAnalysisResult.Step.SKELETON));
    }

    @Test
    void invalidSemanticRepairFallsBackToRawScriptAndContinues() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        when(memoryService.resolveMemoryBlock(anyLong())).thenReturn("");
        String skeletonJson = """
                {"logline":"测试","style_prefix":"","episodes":[
                  {"index":1,"title":"一","logline":"一","hook":"一","characters":["阿洛"],"scenes_hint":[],"climax":"一","next_episode_tease":"二"}],
                 "characters_recurrent":["阿洛"],"world_rules":[]}
                """;
        String scriptJson = """
                {"scenes":[{"index":1,"characters":["阿洛"],"props":[],"dialogue":[]}],"style_prefix":""}
                """;
        String assetsJson = """
                {"assets":[{"name":"阿洛","type":"角色","state_tag":"base","description":"短",
                 "identity_invariants":"短","image_prompt":"","voice_lock":"","must_split":false}]}
                """;
        String shotsJson = """
                {"chapters":[{"index":1,"name":"一","scene_index":1}],"shots":[
                  {"chapter_index":1,"index":1,"title":"一","duration_sec":8,"narrative_goal":"一",
                   "scene":"室内","dialogue":"","continuity_in":"","continuity_out":"",
                   "character_names":["阿洛"],"prop_names":[],"location_name":"","cast_count":1}]}
                """;
        when(gateway.chat(anyLong(), any(TextChatRequest.class)))
                .thenReturn(TextChatResult.of(skeletonJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(scriptJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of("{}", "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of("{}", "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(assetsJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(shotsJson, "m", "stop", 1L, 1L, 0L));

        DramaAnalysisResult result = orchestrator.analyze(DramaAnalysisRequest.fullRun(
                1L, 75L, "s", "原始文本", 1, "1", "m"));

        assertTrue(result.success(), () -> "fallback 后应继续，错误=" + result.stepErrors());
        assertEquals("阿洛", result.script().getScenes().get(0).getCharacters().get(0));
        org.mockito.Mockito.verify(planDataMapper).upsert(eq(75L), eq("semantic_normalization"),
                anyString(), eq("v2-agent-fallback"), org.mockito.ArgumentMatchers.contains("对应关系仍对不上"));
        org.mockito.Mockito.verify(runLog).record(eq("semantic_normalization"), any(), any());
    }

    /**
     * 回归：归一化没过校验时，模型判出的别名分组不能跟着一起丢。
     *
     * <p>测试反馈「用 minimax 解析还是会出现重复提取资产」：14 环境实测（episode 128）里
     * 归一化被判死 → 回退原始剧本 → 「大拖鞋」与「大号塑料拖鞋」各抽了一条。
     * 现在判死时仍按模型的分组把别名统一，同一件东西只留一条资产。
     */
    @Test
    void failedNormalizationStillUnifiesAliasesBeforeAssetExtraction() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        when(memoryService.resolveMemoryBlock(anyLong())).thenReturn("");
        String skeletonJson = """
                {"logline":"测试","style_prefix":"","episodes":[
                  {"index":1,"title":"一","logline":"一","hook":"一","characters":["陈卓"],"scenes_hint":[],"climax":"一","next_episode_tease":"二"}],
                 "characters_recurrent":["陈卓"],"world_rules":[]}
                """;
        String scriptJson = """
                {"scenes":[{"index":1,"characters":["陈卓","陈小卓"],
                  "props":["大号塑料拖鞋","大拖鞋"],
                  "dialogue":[{"speaker":"陈卓","line":"我们走"}]}],"style_prefix":""}
                """;
        // 归一化：别名分组判对了，但剧本里凭空多了一句台词 → 校验过不去
        String semanticJson = """
                {"normalized_script":{"scenes":[
                  {"index":1,"characters":["陈卓"],"props":["大号塑料拖鞋"],
                   "dialogue":[{"speaker":"陈卓","line":"我们走"},
                               {"speaker":"陈卓","line":"凭空多的一句","emotion":""}]}
                ]},"entity_groups":[
                  {"entity_type":"角色","canonical_mention_id":"scene:0.characters:0",
                   "member_mention_ids":["scene:0.characters:0","scene:0.characters:1"],"confidence":0.9},
                  {"entity_type":"道具","canonical_mention_id":"scene:0.props:0",
                   "member_mention_ids":["scene:0.props:0","scene:0.props:1"],"confidence":0.9}
                ],"scene_lineage":[
                  {"normalized_scene_index":1,"source_scene_indexes":[0],
                   "action_source_ids":[],"dialogue_source_ids":["scene:0.dialogue:0"]}
                ]}
                """;
        String assetsJson = """
                {"assets":[
                  {"name":"陈卓","type":"角色","state_tag":"base","description":"男主",
                   "identity_invariants":"短","image_prompt":"","voice_lock":"","must_split":false},
                  {"name":"大号塑料拖鞋","type":"道具","state_tag":"base","description":"拖鞋",
                   "identity_invariants":"塑料","image_prompt":"","voice_lock":"","must_split":false}
                ]}
                """;
        String shotsJson = """
                {"chapters":[{"index":1,"name":"一","scene_index":1}],"shots":[
                  {"chapter_index":1,"index":1,"title":"一","duration_sec":8,"narrative_goal":"一",
                   "scene":"病房","dialogue":"我们走","continuity_in":"","continuity_out":"",
                   "character_names":["陈卓"],"prop_names":["大号塑料拖鞋"],"location_name":"","cast_count":1}]}
                """;
        when(gateway.chat(anyLong(), any(TextChatRequest.class)))
                .thenReturn(TextChatResult.of(skeletonJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(scriptJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(semanticJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(semanticJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(assetsJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(shotsJson, "m", "stop", 1L, 1L, 0L));

        DramaAnalysisResult result = orchestrator.analyze(DramaAnalysisRequest.fullRun(
                1L, 76L, "s", "原始文本", 1, "1", "m"));

        assertTrue(result.success(), () -> "归一化判死后应继续，错误=" + result.stepErrors());
        assertEquals(List.of("陈卓"), result.script().getScenes().get(0).getCharacters(),
                "别名写法应按模型的分组统一");
        assertEquals(List.of("大号塑料拖鞋"), result.script().getScenes().get(0).getProps());
        List<String> assetNames = result.assets().getAssets().stream()
                .map(AssetDraftList.AssetDraft::getName).toList();
        assertEquals(2, assetNames.size(), () -> "同一件东西只该抽一条资产，实际=" + assetNames);
        assertTrue(assetNames.containsAll(List.of("陈卓", "大号塑料拖鞋")),
                () -> "资产里应只有统一后的名字，实际=" + assetNames);
    }

    /**
     * 回归（2026-11-03 线上故障）：MiniMax 把道具写成角色、给名字加前缀（「拖鞋」→「大号塑料拖鞋」）、
     * 还漏掉角色，词表校验一次报出 50 条「不在归一化剧本规范词表」；修复轮只让模型删条目，仍不通过，
     * 整集分析在资产步终止。
     *
     * <p>现在名字与类型由代码从归一化剧本推导，模型输出被投影回规范骨架 —— 资产步死不掉。
     */
    @Test
    void assetsStepSurvivesOffWordTableModelOutput() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        when(memoryService.resolveMemoryBlock(anyLong())).thenReturn("");
        String skeletonJson = """
                {"logline":"测试","style_prefix":"","episodes":[
                  {"index":1,"title":"一","logline":"一","hook":"一","characters":["阿洛"],"scenes_hint":[],"climax":"一","next_episode_tease":"二"}],
                 "characters_recurrent":["阿洛"],"world_rules":[]}
                """;
        String scriptJson = """
                {"scenes":[{"index":1,"characters":["阿洛"],"props":["拖鞋","长椅"],"dialogue":[]}],"style_prefix":""}
                """;
        // 模型把道具写成角色、给名字加前缀，还漏了主角阿洛
        String assetsJson = """
                {"assets":[
                 {"name":"拖鞋","type":"角色","state_tag":"base","description":"一双拖鞋","identity_invariants":"塑料拖鞋","image_prompt":"","voice_lock":"","must_split":false},
                 {"name":"大号塑料拖鞋","type":"角色","state_tag":"base","description":"塑料拖鞋","identity_invariants":"塑料拖鞋","image_prompt":"","voice_lock":"","must_split":false},
                 {"name":"长椅","type":"角色","state_tag":"base","description":"一条长椅","identity_invariants":"木质长椅","image_prompt":"","voice_lock":"","must_split":false}]}
                """;
        String shotsJson = """
                {"chapters":[{"index":1,"name":"一","scene_index":1}],"shots":[
                  {"chapter_index":1,"index":1,"title":"一","duration_sec":8,"narrative_goal":"一",
                   "scene":"室内","dialogue":"","continuity_in":"","continuity_out":"",
                   "character_names":["阿洛"],"prop_names":[],"location_name":"","cast_count":1}]}
                """;
        when(gateway.chat(anyLong(), any(TextChatRequest.class)))
                .thenReturn(TextChatResult.of(skeletonJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(scriptJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of("{}", "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of("{}", "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(assetsJson, "m", "stop", 1L, 1L, 0L))
                .thenReturn(TextChatResult.of(shotsJson, "m", "stop", 1L, 1L, 0L));

        DramaAnalysisResult result = orchestrator.analyze(DramaAnalysisRequest.fullRun(
                1L, 76L, "s", "原始文本", 1, "1", "m"));

        assertTrue(result.success(), () -> "资产步不该再判死: " + result.stepErrors());
        List<AssetDraftList.AssetDraft> assets = result.assets().getAssets();
        assertEquals(3, assets.size(), "资产清单必须等于规范骨架");
        assertEquals("阿洛", assets.get(0).getName());
        assertEquals("角色", assets.get(0).getType());
        assertEquals("拖鞋", assets.get(1).getName());
        assertEquals("道具", assets.get(1).getType(), "道具不能被模型写成角色");
        assertEquals("长椅", assets.get(2).getName());
        assertEquals("道具", assets.get(2).getType());
    }

    @Test
    void downstreamOnly_reusesExistingScript() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        when(memoryService.resolveMemoryBlock(anyLong())).thenReturn("");

        String assetsJson = """
                {"assets":[
                  {"name":"洛白","type":"角色","state_tag":"base","description":"短","identity_invariants":"短","image_prompt":"","voice_lock":"","must_split":false}
                ]}
                """;
        String shotsJson = """
                {"chapters":[{"index":1,"name":"开场","scene_index":1}],
                 "shots":[{"chapter_index":1,"index":1,"title":"开场","duration_sec":8,
                           "narrative_goal":"引子","scene":"教室","dialogue":"我们走吧",
                           "continuity_in":"","continuity_out":"",
                           "character_names":["洛白"],"prop_names":[],"location_name":"教室","cast_count":1}]}
                """;
        when(gateway.chat(anyLong(), any(TextChatRequest.class)))
                .thenReturn(TextChatResult.of(assetsJson, "m", "stop", 100L, 100L, 0L))
                .thenReturn(TextChatResult.of(shotsJson, "m", "stop", 100L, 100L, 0L));

        // 复用已有 script，跳过 skeleton/script 步
        ScriptDoc existingScript = new ScriptDoc();
        ScriptDoc.Scene s1 = new ScriptDoc.Scene();
        s1.setIndex(1);
        s1.setHeading("教室-日");
        s1.setCharacters(List.of("洛白"));
        ScriptDoc.Dialogue d1 = new ScriptDoc.Dialogue();
        d1.setSpeaker("洛白");
        d1.setLine("我们走吧");
        s1.setDialogue(List.of(d1));
        s1.setProps(List.of());
        existingScript.setScenes(List.of(s1));
        existingScript.setStylePrefix("");

        SkeletonDoc existingSkeleton = new SkeletonDoc(
                "logline text", "style prefix",
                List.of(new SkeletonDoc.EpisodeOutline(1, "第一集", "开篇", "钩子",
                        List.of("洛白"), List.of(), "高潮", "下集预告")),
                List.of("洛白"), List.of());

        DramaAnalysisRequest req = DramaAnalysisRequest.downstreamOnly(
                1L, 100L, "sess-1", "原始文本", existingSkeleton, existingScript, "1", "m");
        DramaAnalysisResult result = orchestrator.analyze(req);

        assertTrue(result.allStepsOk(), "应当成功，实际 failedAt=" + result.failedAt());
        // 4 个 slot 都有值（skeleton/script 由下游继承，assets/shots 由本步生成）
        assertEquals(4, result.successfulSteps().size());
        // 应当只调 2 次 LLM（assets + shots）：资产步的名字与类型由代码推导，
        // 模型的命名错误不会再触发 supervisor 修复轮
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(2))
                .chat(anyLong(), any(TextChatRequest.class));
    }

    @Test
    void stepError_recordedInStepErrors() {
        when(promptResolver.resolve(anyString())).thenReturn("PROMPT");
        when(memoryService.resolveMemoryBlock(anyLong())).thenReturn("");
        when(gateway.chat(anyLong(), any(TextChatRequest.class)))
                .thenReturn(TextChatResult.of("garbage", "m", "stop", 100L, 100L, 0L));

        DramaAnalysisRequest req = DramaAnalysisRequest.fullRun(
                1L, 100L, "sess-1", "原始文本", 3, "1", "m");
        DramaAnalysisResult result = orchestrator.analyze(req);

        assertFalse(result.success());
        Map<DramaAnalysisResult.Step, String> errs = result.stepErrors();
        assertTrue(errs.containsKey(DramaAnalysisResult.Step.SKELETON));
        assertTrue(errs.get(DramaAnalysisResult.Step.SKELETON).contains("都没能解析出结果"));
    }
}
