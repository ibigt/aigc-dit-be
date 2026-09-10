package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaTextServiceImplTest {

    private final DramaTextServiceImpl service = new DramaTextServiceImpl();

    @Test
    void engineNeverReportsPending() {
        for (long seriesId : new long[]{0L, 1L, 999L}) {
            String engine = assertDoesNotThrow(() -> service.engine(seriesId));
            assertTrue(DramaTextService.ENGINE_RULES.equals(engine) || DramaTextService.ENGINE_LLM.equals(engine),
                    "engine() 不得返回 pending，否则会被 normalizeEngine 伪装成「乙未合入」");
        }
    }

    @Test
    void parseScriptNeverThrowsOnJunkInput() {
        // DramaService 在事务里同步调用本方法，抛异常会直接堵死导入。
        for (String raw : new String[]{null, "", "   ", "一", "\n\n\n", "：无主台词", "第X场 乱写"}) {
            ScriptDoc doc = assertDoesNotThrow(() -> service.parseScript(7L, raw));
            assertTrue(doc.getScenes().size() <= 2);
        }
    }

    @Test
    void parsedDocumentRoundTripsThroughJsonbMap() {
        ScriptDoc doc = service.parseScript(7L, "第一场 幻梦侦探社 日 内\n洛白：开始了。");

        java.util.Map<String, Object> map = ScriptDocs.toMap(doc);
        assertEquals(1, ((java.util.Collection<?>) map.get("scenes")).size());

        ScriptDoc back = ScriptDocs.fromMap(map);
        assertEquals("幻梦侦探社", back.getScenes().get(0).getLocation());
        assertEquals("洛白", back.getScenes().get(0).getCharacters().get(0));
    }
}
