package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.DramaContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptDocsTest {

    @Test
    void toMapKeepsContractKeys() {
        ScriptDoc doc = new ScriptDoc();
        doc.setTitle("幻梦侦探社");
        doc.setStylePrefix("二次元漫画风");
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setCharacters(List.of("洛白"));
        doc.setScenes(List.of(scene));

        Map<String, Object> map = ScriptDocs.toMap(doc);
        assertEquals("幻梦侦探社", map.get("title"));
        assertEquals("二次元漫画风", map.get("style_prefix"));
        assertTrue(ScriptDocs.hasScenes(map));
        assertTrue(ScriptDocs.lockable(null, map));
        assertEquals("二次元漫画风", ScriptDocs.fromMap(map).getStylePrefix());
    }

    @Test
    void lockableRequiresSourceOrScenes() {
        assertFalse(ScriptDocs.lockable("  ", null));
        assertTrue(ScriptDocs.lockable("原文", null));
        assertFalse(ScriptDocs.hasScenes(Map.of("scenes", List.of())));
        assertEquals(DramaContract.ENGINE_PENDING, DramaContract.normalizeEngine("other"));
        assertEquals(DramaContract.ENGINE_LLM, DramaContract.normalizeEngine("llm"));
        assertEquals("文本模型调用失败", DramaContract.clipError("  "));
        assertEquals("文本模型超时", DramaContract.clipError("  文本模型超时  "));
    }
}
