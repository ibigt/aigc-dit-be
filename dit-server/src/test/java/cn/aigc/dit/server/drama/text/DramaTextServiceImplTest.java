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
    void engineNeverReportsPending() {
        DramaTextServiceImpl service = boundService();

        for (long seriesId : new long[]{UNBOUND, BOUND}) {
            String engine = service.engine(seriesId);
            assertTrue(DramaTextService.ENGINE_RULES.equals(engine) || DramaTextService.ENGINE_LLM.equals(engine),
                    "engine() 不得返回 pending，否则会被 normalizeEngine 伪装成「乙未合入」");
        }
    }

    @Test
    void engineIsNotCrossContaminatedBetweenSeries() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(reply(GOOD_JSON));

        service.parseScript(BOUND, RAW);

        assertEquals(DramaTextService.ENGINE_RULES, service.engine(UNBOUND),
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
}
