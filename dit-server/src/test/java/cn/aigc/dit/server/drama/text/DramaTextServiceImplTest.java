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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void unboundSeriesUsesRules() {
        DramaTextServiceImpl service = boundService();

        ScriptDoc doc = service.parseScript(UNBOUND, RAW);

        assertEquals(DramaTextService.ENGINE_RULES, service.engine(UNBOUND));
        assertEquals(1, doc.getScenes().size());
        assertEquals("幻梦侦探社", doc.getScenes().get(0).getLocation());
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
    void emptyScenesRetriesOnceThenFallsBackWithoutClaimingLlm() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenReturn(reply("{\"scenes\":[]}"));

        ScriptDoc doc = service.parseScript(BOUND, RAW);

        verify(gateway, times(2)).chat(anyLong(), any());
        assertEquals(DramaTextService.ENGINE_RULES, service.engine(BOUND), "回落却报 llm 会让 engine 与 script 不同源");
        assertEquals(1, doc.getScenes().size());
        assertEquals("幻梦侦探社", doc.getScenes().get(0).getLocation());
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
    void transportFailureFallsBackWithoutAnotherRoundTrip() {
        DramaTextServiceImpl service = boundService();
        when(gateway.chat(anyLong(), any())).thenThrow(new BizException(ErrorCode.INTERNAL, "文本模型超时"));

        ScriptDoc doc = service.parseScript(BOUND, RAW);

        verify(gateway, times(1)).chat(anyLong(), any());
        assertEquals(DramaTextService.ENGINE_RULES, service.engine(BOUND));
        assertEquals(1, doc.getScenes().size());
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
    void unreadableContentYieldsNullNotException() {
        assertNull(DramaTextServiceImpl.readScript(null));
        assertNull(DramaTextServiceImpl.readScript("   "));
        assertNull(DramaTextServiceImpl.readScript("{"));
        assertNull(DramaTextServiceImpl.readScript("{\"scenes\":[],\"title\":\"x\"}"));
    }

    @Test
    void parseScriptNeverThrowsOnJunkInput() {
        DramaTextServiceImpl service = boundService();

        // DramaService 在事务里同步调用本方法，抛异常会直接堵死导入。
        for (String raw : new String[]{null, "", "   ", "一", "\n\n\n", "：无主台词", "第X场 乱写"}) {
            for (long seriesId : new long[]{UNBOUND, BOUND}) {
                if (seriesId == BOUND) {
                    when(gateway.chat(anyLong(), any())).thenReturn(reply("不是JSON"));
                }
                ScriptDoc doc = assertDoesNotThrow(() -> service.parseScript(seriesId, raw));
                assertTrue(doc.getScenes().size() <= 2);
            }
        }
    }

    @Test
    void parsedDocumentRoundTripsThroughJsonbMap() {
        DramaTextServiceImpl service = boundService();

        ScriptDoc doc = service.parseScript(UNBOUND, RAW);
        Map<String, Object> map = ScriptDocs.toMap(doc);

        assertEquals(1, ((Collection<?>) map.get("scenes")).size());
        ScriptDoc back = ScriptDocs.fromMap(map);
        assertEquals("幻梦侦探社", back.getScenes().get(0).getLocation());
        assertEquals(List.of("洛白"), back.getScenes().get(0).getCharacters());
    }
}
