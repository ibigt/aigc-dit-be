package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaTextServiceImplTest {

    private static final long UNBOUND = 1L;
    private static final long BOUND = 2L;
    private static final long DISABLED_BINDING = 3L;
    private static final String RAW = "第一场 幻梦侦探社 日 内\n洛白：开始了。";

    private static final String GOOD_JSON = """
            {"title":"幻梦侦探社","logline":"一句话","scenes":[
              {"index":9,"slug":"office","heading":"幻梦侦探社 日 内","characters":["洛白","局长"],
               "props":["古铜怀表"],"location":"幻梦侦探社","time":"日","interior":true,
               "action":"风扇转动。","sound":"风扇","dialogue":[{"speaker":"局长","line":"别睡了。"}]}]}
            """;

    @Mock
    private TextChatGateway gateway;

    private DramaTextServiceImpl boundService() {
        DramaTextServiceImpl service = new DramaTextServiceImpl(gateway);
        org.mockito.Mockito.lenient().when(gateway.resolve(UNBOUND)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(gateway.resolve(BOUND)).thenReturn(Optional.of(model()));
        org.mockito.Mockito.lenient().when(gateway.resolve(DISABLED_BINDING))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, TextChatGateway.BINDING_UNUSABLE));
        return service;
    }

    private static ResolvedTextModel model() {
        return new ResolvedTextModel(5L, "Agnes 对话", TextAdapterCatalog.AGNES_TEXT,
                "官方", "https://api.agnes-ai.cn/v1", TextAdapterCatalog.AGNES_CHAT_MODEL);
    }

    private static TextChatResult reply(String content) {
        return TextChatResult.of(content, "agnes-2.5-flash", "stop");
    }

    @Test
    void unboundSeriesRequiresTextModel() {
        DramaTextServiceImpl service = boundService();

        BizException error = assertThrows(BizException.class, () -> service.parseScript(UNBOUND, RAW));

        assertTrue(error.getMessage().contains("未绑定文本模型"));
        verify(gateway, never()).chat(anyLong(), any());
    }

    @Test
    void durationGuardBumpsDurationToSpokenLength() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setDurationSec(5);
        // 台词 25 字，4 字/秒需要 7 秒，5 秒念不完。
        shot.setDialogue("局长（愤怒）：今天我们必须把这件事情查个水落石出不能让任何人跑了");
        list.setShots(new java.util.ArrayList<>(List.of(shot)));

        int extended = DramaTextServiceImpl.fitDurationsToDialogue(list);

        assertEquals(1, extended);
        assertEquals(7, shot.getDurationSec());
    }

    @Test
    void durationGuardCapsAtFifteenSeconds() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setDurationSec(8);
        // 台词 70 字需要 18 秒，单镜上限 15 秒，封顶保留。
        shot.setDialogue("旁白：零一二三四五六七八九".repeat(7));
        list.setShots(new java.util.ArrayList<>(List.of(shot)));

        int extended = DramaTextServiceImpl.fitDurationsToDialogue(list);

        assertEquals(1, extended);
        assertEquals(15, shot.getDurationSec());
    }

    @Test
    void durationGuardCountsOnlySpeakableCharsIgnoringPunctuation() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft lightPunct = new ShotDraftList.ShotDraft();
        lightPunct.setDurationSec(5);
        // 口播字仅 14（顿号/省略号/问号/感叹号共 9 个不发音）；连标点数成 23 字会错抬到 6 秒。
        lightPunct.setDialogue("局长：你、你、你……到底想说什么？说、清、楚？马上！");
        ShotDraftList.ShotDraft spokenHeavy = new ShotDraftList.ShotDraft();
        spokenHeavy.setDurationSec(5);
        // 无标点 22 口播字，5 秒念不完要抬到 6 秒。
        spokenHeavy.setDialogue("局长：这件事必须查个水落石出一查到底绝不姑息马上办");
        list.setShots(new java.util.ArrayList<>(List.of(lightPunct, spokenHeavy)));

        int extended = DramaTextServiceImpl.fitDurationsToDialogue(list);

        assertEquals(1, extended);
        assertEquals(5, lightPunct.getDurationSec(),
                "标点不发音，不得把标点算进口播字数而虚抬时长");
        assertEquals(6, spokenHeavy.getDurationSec());
    }

    @Test
    void durationGuardNeverDecreasesAndSkipsSilentShots() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft spoken = new ShotDraftList.ShotDraft();
        spoken.setDurationSec(12);
        spoken.setDialogue("局长（愤怒）：你给我站住！");
        ShotDraftList.ShotDraft silent = new ShotDraftList.ShotDraft();
        silent.setDurationSec(6);
        silent.setDialogue("");
        list.setShots(new java.util.ArrayList<>(List.of(spoken, silent)));

        int extended = DramaTextServiceImpl.fitDurationsToDialogue(list);

        assertEquals(0, extended);
        assertEquals(12, spoken.getDurationSec());
        assertEquals(6, silent.getDurationSec());
    }

    @Test
    void overloadedDialogueShotSplitsIntoSpeakableShots() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setChapterIndex(1);
        shot.setIndex(1);
        shot.setDurationSec(15);
        shot.setNarrativeGoal("中景。三人对峙。");
        shot.setScene("研发部日戏");
        // 三句各 40 字（去掉「旁白：」前缀后），合计 120 字需 30 秒，单镜 15 秒装不下。
        String line = "旁白：" + "零一二三四五六七八九".repeat(4);
        shot.setDialogue(String.join("\n", List.of(line, line, line)));
        shot.setCharacterNames(List.of("张总"));
        shot.setContinuityIn("风扇入画");
        shot.setContinuityOut("张总转身");
        list.setShots(new java.util.ArrayList<>(List.of(shot)));

        int split = DramaTextServiceImpl.splitOverloadedShots(list);

        assertEquals(1, split);
        List<ShotDraftList.ShotDraft> shots = list.getShots();
        assertEquals(3, shots.size());
        for (int i = 0; i < shots.size(); i++) {
            ShotDraftList.ShotDraft part = shots.get(i);
            assertEquals(i + 1, part.getIndex());
            assertEquals(1, part.getChapterIndex());
            // 拆分必须按原对白行整行搬运，说话人前缀一个字不能丢。
            assertEquals(line, part.getDialogue());
            assertEquals(10, part.getDurationSec());
            assertEquals("研发部日戏", part.getScene());
            assertEquals(List.of("张总"), part.getCharacterNames());
            assertEquals(i == 0 ? "风扇入画" : "", part.getContinuityIn());
            assertEquals(i == 2 ? "张总转身" : "", part.getContinuityOut());
        }
    }

    @Test
    void speakableShotsAreNotSplit() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft speakable = new ShotDraftList.ShotDraft();
        speakable.setChapterIndex(1);
        speakable.setIndex(1);
        speakable.setDurationSec(12);
        speakable.setDialogue("局长：今天我们必须把这件事情查个水落石出不能让任何人跑了");
        list.setShots(new java.util.ArrayList<>(List.of(speakable)));

        int split = DramaTextServiceImpl.splitOverloadedShots(list);

        assertEquals(0, split);
        assertEquals(1, list.getShots().size());
        assertEquals(speakable, list.getShots().get(0));
    }

    @Test
    void boundSeriesUsesLlmAndSendsJsonMode() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(reply(GOOD_JSON));

        ScriptDoc doc = service.parseScript(BOUND, RAW);

        assertEquals(DramaTextService.ENGINE_LLM, service.engine(BOUND));
        assertEquals("一句话", doc.getLogline());
        assertEquals(1, doc.getScenes().size());
        assertEquals("风扇转动。", doc.getScenes().get(0).getAction());
        ArgumentCaptor<TextChatRequest> sent = ArgumentCaptor.forClass(TextChatRequest.class);
        verify(gateway).chat(eq(BOUND), sent.capture());
        assertTrue(sent.getValue().jsonMode(), "必须走 JSON mode，否则自由文本会毁掉入库");
        assertTrue(sent.getValue().getUser().contains(RAW));
    }

    @Test
    void markdownFenceAndProseAreTolerated() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(reply("```json\n" + GOOD_JSON + "\n```\n以上。"));

        assertNotNull(service.parseScript(BOUND, RAW).getScenes());
        assertEquals(DramaTextService.ENGINE_LLM, service.engine(BOUND));
    }

    @Test
    void modelIndexIsRepairedToPosition() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(reply(GOOD_JSON));

        ScriptDoc.Scene scene = service.parseScript(BOUND, RAW).getScenes().get(0);

        assertEquals(1, scene.getIndex(), "模型写的 index=9 必须按位置定序");
    }

    @Test
    void emptyScenesRetriesOnceThenFailsExplicitly() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(reply("{\"scenes\":[]}"));

        BizException error = assertThrows(BizException.class, () -> service.parseScript(BOUND, RAW));

        verify(gateway, times(2)).chat(anyLong(), any());
        assertTrue(error.getMessage().contains("ScriptDoc"));
    }

    @Test
    void secondAttemptIsAccepted() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any()))
                .thenReturn(reply("我不是JSON"))
                .thenReturn(reply(GOOD_JSON));

        ScriptDoc doc = service.parseScript(BOUND, RAW);

        verify(gateway, times(2)).chat(anyLong(), any());
        assertEquals(DramaTextService.ENGINE_LLM, service.engine(BOUND));
        assertEquals("幻梦侦探社", doc.getScenes().get(0).getHeading().split("\\s")[0]);
    }

    @Test
    void transportFailureSurfacesWithoutAnotherRoundTrip() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenThrow(new BizException(ErrorCode.INTERNAL, "文本模型超时"));

        BizException error = assertThrows(BizException.class, () -> service.parseScript(BOUND, RAW));

        verify(gateway, times(1)).chat(anyLong(), any());
        assertEquals("文本模型超时", error.getMessage());
    }

    @Test
    void disabledBindingSurfacesInsteadOfSilentDegradation() {
        DramaTextServiceImpl service = boundService();

        BizException error = assertThrows(BizException.class, () -> service.parseScript(DISABLED_BINDING, RAW));
        assertEquals(TextChatGateway.BINDING_UNUSABLE, error.getMessage());
        assertThrows(BizException.class, () -> service.engine(DISABLED_BINDING));
    }

    @Test
    void engineReportsPendingWhenUnbound() {
        DramaTextServiceImpl service = boundService();

        assertEquals(DramaTextService.ENGINE_PENDING, service.engine(UNBOUND));
        assertEquals(DramaTextService.ENGINE_LLM, service.engine(BOUND));
    }

    @Test
    void engineIsNotCrossContaminatedBetweenSeries() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(reply(GOOD_JSON));

        service.parseScript(BOUND, RAW);

        assertEquals(DramaTextService.ENGINE_PENDING, service.engine(UNBOUND),
                "同线程刚解析过 BOUND，不得把 UNBOUND 也报成 llm");
    }

    @Test
    void nullAndBlankCollectionsSurviveNormalize() {
        ScriptDoc doc = DramaTextServiceImpl.readScript("""
                {"scenes":[{"index":1,"heading":"街道 夜 外","characters":null,"props":["  "],
                  "dialogue":[{"speaker":"甲","line":""},null],"location":"街道"}]}
                """);

        assertNotNull(doc);
        ScriptDoc.Scene scene = doc.getScenes().get(0);
        assertTrue(scene.getCharacters().isEmpty());
        assertTrue(scene.getProps().isEmpty());
        assertTrue(scene.getDialogue().isEmpty());
        assertNull(scene.getInterior(), "未提供的布尔字段留 null，不替模型编造内外景");
    }

    @Test
    void generatedScriptKeepsOnlyThreeDistinctMainProps() {
        ScriptDoc doc = DramaTextServiceImpl.readScript("""
                {"scenes":[{"index":1,"heading":"办公室 日 内","characters":[],
                  "props":["关键钥匙","证据手机","身份徽章","关键钥匙","背景水杯","普通椅子"],
                  "dialogue":[],"location":"办公室"}]}
                """);

        assertNotNull(doc);
        assertEquals(List.of("关键钥匙", "证据手机", "身份徽章"), doc.getScenes().get(0).getProps(),
                "模型应按重要性排序，服务端兜底去重并只保留前三个主要道具");
    }

    @Test
    void promptsExplicitlyLimitPropsToMainOnes() {
        assertTrue(DramaTextPrompts.scriptSystem().contains("每场最多 3 个"));
        assertTrue(DramaTextPrompts.scriptSystem().contains("没有主要道具时返回空数组"));
        assertTrue(DramaTextPrompts.assetSystem().contains("只允许从各场 props 数组引用"));
        assertTrue(DramaTextPrompts.assetSystem().contains("image_prompt 一律输出空字符串"));
        assertTrue(DramaTextPrompts.assetSystem().contains("上装/外套/下装/鞋履"));
    }

    @Test
    void unreadableContentYieldsNullNotException() {
        assertNull(DramaTextServiceImpl.readScript(null));
        assertNull(DramaTextServiceImpl.readScript("   "));
        assertNull(DramaTextServiceImpl.readScript("{"));
        assertNull(DramaTextServiceImpl.readScript("{\"scenes\":[],\"title\":\"x\"}"));
    }

    @Test
    void invalidOutputReasonExplainsEmptyMalformedAndContractFailures() {
        assertEquals("模型返回 content 为空",
                DramaTextServiceImpl.invalidOutputReason(" ", "ScriptDoc"));
        assertEquals("返回内容中没有完整 JSON 对象",
                DramaTextServiceImpl.invalidOutputReason("不是 JSON", "ScriptDoc"));
        assertTrue(DramaTextServiceImpl.invalidOutputReason("{\"scenes\":[}", "ScriptDoc")
                .startsWith("JSON 解析失败 line="));
        assertEquals("scenes 缺失或为空",
                DramaTextServiceImpl.invalidOutputReason("{\"scenes\":[]}", "ScriptDoc"));
        assertEquals("assets 缺失或为空",
                DramaTextServiceImpl.invalidOutputReason("{\"assets\":[]}", "AssetDraftList"));
        assertEquals("shots 缺失或为空",
                DramaTextServiceImpl.invalidOutputReason("{\"shots\":[]}", "ShotDraftList"));
        assertEquals("shots 缺失或为空",
                DramaTextServiceImpl.invalidOutputReason("""
                        {
                          // MiniMax 偶发注释
                          "shots": [],
                        }
                        """, "ShotDraftList"),
                "注释/尾逗号应能解析，再落到合同空数组诊断");
    }

    @Test
    void parseJsonRecoversCommentsAndTrailingCommasWithoutChangingStrictJson() {
        ScriptDoc strict = DramaTextServiceImpl.parseJson(GOOD_JSON, ScriptDoc.class);
        assertNotNull(strict);
        assertEquals("幻梦侦探社", strict.getScenes().get(0).getHeading().split("\\s")[0]);

        ScriptDoc commented = DramaTextServiceImpl.readScript("""
                {
                  // chapter notes
                  "title": "幻梦侦探社",
                  "scenes": [{
                    "index": 1,
                    "heading": "幻梦侦探社 日 内",
                    "characters": ["洛白"],
                    "props": [],
                    "dialogue": [],
                    "location": "幻梦侦探社",
                    "action": "风扇转动。 https://example.com/a",
                  }],
                }
                """);
        assertNotNull(commented);
        assertEquals("风扇转动。 https://example.com/a", commented.getScenes().get(0).getAction(),
                "字符串内的 // 不得被当成注释剥掉");
    }

    @Test
    void extractAssetsRequests16384TokensForEnrichment() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(reply("""
                {"assets":[{"name":"洛白","type":"角色","state_tag":"base",
                  "description":"青年男性","identity_invariants":"同一张脸","image_prompt":""}]}
                """));
        ScriptDoc script = DramaTextServiceImpl.readScript(GOOD_JSON);

        service.extractAssets(BOUND, script);

        ArgumentCaptor<TextChatRequest> sent = ArgumentCaptor.forClass(TextChatRequest.class);
        verify(gateway).chat(eq(BOUND), sent.capture());
        assertEquals(16384, sent.getValue().getMaxTokens());
    }

    @Test
    void lengthFinishReasonBumpsMaxTokensOnRetry() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any()))
                .thenReturn(TextChatResult.of("{\"assets\":[{\"name\":\"洛白\"", "minimax-m1", "length"))
                .thenReturn(reply("""
                        {"assets":[{"name":"洛白","type":"角色","state_tag":"base",
                          "description":"青年男性","identity_invariants":"同一张脸","image_prompt":"三视图"}]}
                        """));
        ScriptDoc script = DramaTextServiceImpl.readScript(GOOD_JSON);

        service.extractAssets(BOUND, script);

        ArgumentCaptor<TextChatRequest> sent = ArgumentCaptor.forClass(TextChatRequest.class);
        verify(gateway, times(2)).chat(eq(BOUND), sent.capture());
        assertEquals(16384, sent.getAllValues().get(0).getMaxTokens());
        assertEquals(32768, sent.getAllValues().get(1).getMaxTokens());
        assertTrue(sent.getAllValues().get(1).getUser().contains("finish_reason=length"));
    }

    @Test
    void truncatedByLengthRecognizesVendorAliases() {
        assertTrue(DramaTextServiceImpl.truncatedByLength("length"));
        assertTrue(DramaTextServiceImpl.truncatedByLength("MAX_TOKENS"));
        assertTrue(!DramaTextServiceImpl.truncatedByLength("stop"));
        assertEquals(16384, DramaTextServiceImpl.nextMaxTokens(8192));
        assertEquals(DramaTextServiceImpl.MAX_JSON_TOKENS,
                DramaTextServiceImpl.nextMaxTokens(DramaTextServiceImpl.MAX_JSON_TOKENS));
    }

    @Test
    void parseScriptRejectsJunkInsteadOfInventingRuleBasedScenes() {
        DramaTextServiceImpl service = boundService();

        when(gateway.chat(anyLong(), any())).thenReturn(reply("不是JSON"));
        assertThrows(BizException.class, () -> service.parseScript(BOUND, "第X场 乱写"));
        assertThrows(BizException.class, () -> service.parseScript(UNBOUND, RAW));
    }

    @Test
    void parsedDocumentRoundTripsThroughJsonbMap() {
        DramaTextServiceImpl service = boundService();

        ScriptDoc doc = DramaTextServiceImpl.readScript(GOOD_JSON);
        Map<String, Object> map = ScriptDocs.toMap(doc);

        assertEquals(1, ((Collection<?>) map.get("scenes")).size());
        ScriptDoc back = ScriptDocs.fromMap(map);
        assertEquals("幻梦侦探社", back.getScenes().get(0).getLocation());
        assertEquals(List.of("洛白", "局长"), back.getScenes().get(0).getCharacters());
    }

    @Test
    void normalizeShotsAcceptsWhenEveryScriptLineLandsInSomeShotDialogue() {
        ScriptDoc script = scriptWith(
                new String[][]{
                        {"局长", "别睡了。"}
                });
        ShotDraftList in = new ShotDraftList();
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setChapterIndex(1);
        shot.setIndex(1);
        shot.setTitle("叫人");
        shot.setDurationSec(5);
        shot.setNarrativeGoal("局长推门。");
        shot.setScene("办公室");
        shot.setDialogue("局长：别睡了。");
        shot.setCharacterNames(List.of("局长"));
        shot.setLocationName("办公室");
        shot.setCastCount(1);
        in.setShots(new java.util.ArrayList<>(List.of(shot)));

        ShotDraftList out = DramaTextServiceImpl.normalizeShots(in, script, List.of("局长", "办公室"));

        assertNotNull(out, "剧本台词全部落到分镜 dialogue 时应当通过合同校验");
        assertEquals(1, out.getShots().size());
    }

    @Test
    void normalizeShotsRejectsWhenScriptLineIsDroppedFromShotDialogue() {
        ScriptDoc script = scriptWith(
                new String[][]{
                        {"局长", "别睡了。"},
                        {"洛白", "我马上起来，别催了。"}
                });
        ShotDraftList in = new ShotDraftList();
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setChapterIndex(1);
        shot.setIndex(1);
        shot.setTitle("叫人");
        shot.setDurationSec(5);
        shot.setNarrativeGoal("局长推门。");
        shot.setScene("办公室");
        // 模型把第二句台词整句丢掉——结构合法，应当被合同校验拦下。
        shot.setDialogue("局长：别睡了。");
        shot.setCharacterNames(List.of("局长"));
        shot.setLocationName("办公室");
        shot.setCastCount(1);
        in.setShots(new java.util.ArrayList<>(List.of(shot)));

        ShotDraftList out = DramaTextServiceImpl.normalizeShots(in, script, List.of("局长", "洛白", "办公室"));

        assertNull(out, "台词整句丢失的分镜必须视为合同失败，由现有重试机制接管");
    }

    @Test
    void normalizeShotsCountsNarrationLinesAsCoveredToo() {
        ScriptDoc script = scriptWith(
                new String[][]{
                        {"局长", "加班费到底什么时候发？"},
                        {"旁白", "夜色渐深。"}
                });
        ShotDraftList in = new ShotDraftList();
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setChapterIndex(1);
        shot.setIndex(1);
        shot.setTitle("质问");
        shot.setDurationSec(5);
        shot.setNarrativeGoal("局长质问。");
        shot.setScene("办公室");
        shot.setDialogue("局长：加班费到底什么时候发？\n旁白：夜色渐深。");
        shot.setCharacterNames(List.of("局长"));
        shot.setLocationName("办公室");
        shot.setCastCount(1);
        in.setShots(new java.util.ArrayList<>(List.of(shot)));

        ShotDraftList out = DramaTextServiceImpl.normalizeShots(in, script, List.of("局长", "办公室"));

        assertNotNull(out, "旁白句也按口播字串纳入覆盖校验，不能只数角色对白");
    }

    @Test
    void normalizeShotsSkipsCoverageCheckForDialogueLessScripts() {
        ScriptDoc script = scriptWith(new String[][]{});
        ShotDraftList in = new ShotDraftList();
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setChapterIndex(1);
        shot.setIndex(1);
        shot.setTitle("动作");
        shot.setDurationSec(8);
        shot.setNarrativeGoal("洛白起身。");
        shot.setScene("办公室");
        shot.setDialogue("");
        shot.setCharacterNames(List.of("洛白"));
        shot.setLocationName("办公室");
        shot.setCastCount(1);
        in.setShots(new java.util.ArrayList<>(List.of(shot)));

        ShotDraftList out = DramaTextServiceImpl.normalizeShots(in, script, List.of("洛白", "办公室"));

        assertNotNull(out, "纯动作剧本（无台词）不得被台词覆盖校验误伤");
    }

    private static ScriptDoc scriptWith(String[][] lines) {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setSlug("office");
        scene.setHeading("办公室 日 内");
        scene.setLocation("办公室");
        scene.setTime("日");
        scene.setInterior(Boolean.TRUE);
        java.util.List<ScriptDoc.Dialogue> dialogue = new java.util.ArrayList<>();
        for (String[] pair : lines) {
            ScriptDoc.Dialogue bit = new ScriptDoc.Dialogue();
            bit.setSpeaker(pair[0]);
            bit.setLine(pair[1]);
            bit.setEmotion("");
            dialogue.add(bit);
        }
        scene.setDialogue(dialogue);
        doc.setScenes(new java.util.ArrayList<>(List.of(scene)));
        return doc;
    }
}
