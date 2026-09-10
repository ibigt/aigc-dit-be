package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeNameGuardTest {

    private static final List<String> LOCKED = List.of("洛白", "夏安", "古铜怀表", "幻梦侦探社", "洛白·入梦");

    private static ScriptDoc docWith(String character, String prop, String location, String speaker) {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.getCharacters().add(character);
        scene.getProps().add(prop);
        scene.setLocation(location);
        ScriptDoc.Dialogue line = new ScriptDoc.Dialogue();
        line.setSpeaker(speaker);
        line.setLine("……");
        scene.getDialogue().add(line);
        doc.getScenes().add(scene);
        return doc;
    }

    @Test
    void secondEpisodeStillReferencesLockedNames() {
        ScriptDoc doc = EpisodeNameGuard.applyTo(
                docWith("洛白", "古铜怀表", "幻梦侦探社", "夏安"), LOCKED);

        ScriptDoc.Scene scene = doc.getScenes().get(0);
        assertEquals(List.of("洛白"), scene.getCharacters());
        assertEquals(List.of("古铜怀表"), scene.getProps());
        assertEquals("幻梦侦探社", scene.getLocation());
        assertEquals("夏安", scene.getDialogue().get(0).getSpeaker());
    }

    @Test
    void renamedProtagonistIsFoldedBack() {
        // 「洛白（少年）」「小洛白」这类写法等于给同一角色发明第二张脸。
        ScriptDoc doc = EpisodeNameGuard.applyTo(
                docWith("洛白（少年）", "古铜怀表", "幻梦侦探社", "洛白"), LOCKED);

        assertEquals(List.of("洛白"), doc.getScenes().get(0).getCharacters());
    }

    @Test
    void newStateAssetIsKeptInCanonicalForm() {
        ScriptDoc doc = EpisodeNameGuard.applyTo(
                docWith("洛白·湿身", "古铜怀表", "幻梦侦探社", "洛白"), LOCKED);

        assertEquals(List.of("洛白·湿身"), doc.getScenes().get(0).getCharacters());
    }

    @Test
    void genuinelyNewCharacterIsAllowed() {
        ScriptDoc doc = EpisodeNameGuard.applyTo(
                docWith("法医老周", "证物袋", "旧码头", "法医老周"), LOCKED);

        ScriptDoc.Scene scene = doc.getScenes().get(0);
        assertEquals(List.of("法医老周"), scene.getCharacters());
        assertEquals(List.of("证物袋"), scene.getProps());
        assertEquals("旧码头", scene.getLocation());
    }

    @Test
    void emptyLockedNamesLeaveDocumentUntouched() {
        ScriptDoc doc = EpisodeNameGuard.applyTo(docWith("洛白", "怀表", "侦探社", "洛白"), List.of());

        assertEquals(List.of("洛白"), doc.getScenes().get(0).getCharacters());
        assertEquals(List.of("怀表"), doc.getScenes().get(0).getProps());
    }

    @Test
    void duplicateNamesAfterFoldingAreMerged() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.getCharacters().addAll(List.of("洛白", "洛白（少年）", "夏安"));
        doc.getScenes().add(scene);

        EpisodeNameGuard.applyTo(doc, LOCKED);

        assertEquals(List.of("洛白", "夏安"), doc.getScenes().get(0).getCharacters());
        assertTrue(doc.getScenes().get(0).getProps().isEmpty());
    }
}
