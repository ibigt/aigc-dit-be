package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaDialogueEmotionNarrationTest {

    @Test
    void sanitizeEmotionClosedSet() {
        assertEquals("愤怒", DramaDialogueSpeakers.sanitizeEmotion("愤怒"));
        assertNull(DramaDialogueSpeakers.sanitizeEmotion(""));
        assertNull(DramaDialogueSpeakers.sanitizeEmotion("狂喜"));
        assertNull(DramaDialogueSpeakers.sanitizeEmotion(null));
    }

    @Test
    void narrationAliasesNormalizeToCanonical() {
        assertEquals("旁白", DramaDialogueSpeakers.normalizeSpeaker("VO"));
        assertEquals("旁白", DramaDialogueSpeakers.normalizeSpeaker("画外音"));
        assertEquals("旁白", DramaDialogueSpeakers.normalizeSpeaker("解说"));
        assertEquals("旁白", DramaDialogueSpeakers.normalizeSpeaker("OS"));
        assertEquals("局长", DramaDialogueSpeakers.normalizeSpeaker("局长"));
        assertTrue(DramaDialogueSpeakers.isNarration("旁白音"));
        assertFalse(DramaDialogueSpeakers.isRealCharacterName("旁白"));
    }

    @Test
    void scriptParserKeepsNarrationOutOfCharacters() {
        ScriptDoc doc = ScriptParser.parse("""
                第一场 夜巷 夜 外
                人物：洛白、旁白
                VO：夜色渐深。
                洛白：别跟过来。
                """);
        ScriptDoc.Scene scene = doc.getScenes().get(0);
        assertFalse(scene.getCharacters().contains("旁白"));
        assertFalse(scene.getCharacters().contains("VO"));
        assertTrue(scene.getCharacters().contains("洛白"));
        assertEquals("旁白", scene.getDialogue().get(0).getSpeaker());
        assertNull(scene.getDialogue().get(0).getEmotion());
    }

    @Test
    void assetDraftsSkipNarrationCharacter() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setCharacters(List.of("洛白", "旁白", "画外音"));
        scene.setLocation("夜巷");
        doc.setScenes(List.of(scene));

        AssetDraftList list = DramaAssetDrafts.fromScript(doc);
        assertTrue(list.getAssets().stream().noneMatch(item ->
                DramaDialogueSpeakers.isNarration(item.getName())));
        assertTrue(list.getAssets().stream().anyMatch(item -> "洛白".equals(item.getName())));
    }

    @Test
    void formatLineSerializesEmotionAndNarration() {
        ScriptDoc.Dialogue angry = new ScriptDoc.Dialogue();
        angry.setSpeaker("局长");
        angry.setLine("你给我站住！");
        angry.setEmotion("愤怒");

        ScriptDoc.Dialogue plain = new ScriptDoc.Dialogue();
        plain.setSpeaker("洛白");
        plain.setLine("知道了。");

        ScriptDoc.Dialogue narration = new ScriptDoc.Dialogue();
        narration.setSpeaker("VO");
        narration.setLine("夜色渐深。");
        narration.setEmotion("悲伤");

        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setHeading("夜巷 夜 外");
        scene.setCharacters(List.of("局长", "洛白"));
        scene.setLocation("夜巷");
        scene.setAction("局长追出。");
        scene.setDialogue(List.of(angry, plain, narration));
        doc.setScenes(List.of(scene));

        var shots = DramaShotDrafts.fromScript(doc, List.of("局长", "洛白", "夜巷"), 8).getShots();
        assertTrue(shots.stream().anyMatch(shot ->
                shot.getDialogue() != null && shot.getDialogue().contains("局长（愤怒）：你给我站住！")));
        assertTrue(shots.stream().anyMatch(shot ->
                shot.getDialogue() != null && shot.getDialogue().contains("旁白：夜色渐深。")));
        assertTrue(shots.stream().noneMatch(shot ->
                shot.getDialogue() != null && shot.getDialogue().contains("旁白（")));
        assertTrue(shots.stream().noneMatch(shot ->
                shot.getCharacterNames() != null && shot.getCharacterNames().contains("旁白")));
    }

    @Test
    void compilerInjectsEmotionToneAndPerformanceWithoutEmotionInCaption() {
        String dialogue = "局长（愤怒）：你给我站住！\n洛白：知道了。";
        String constraint = DramaStoryboardCompiler.spokenDialogueConstraint(dialogue);

        assertTrue(constraint.contains("角色“局长”以愤怒的语气逐字说出：“你给我站住！”"));
        assertTrue(constraint.contains("口型与每个字严格同步"));
        assertTrue(constraint.contains("对白表演："));
        assertTrue(constraint.contains("呈现愤怒神态"));
        assertTrue(constraint.contains("角色“洛白”逐字说出：“知道了。”"));
        assertEquals(List.of("你给我站住！", "知道了。"), DramaStoryboardCompiler.captionLines(dialogue));
        assertFalse(DramaStoryboardCompiler.captionLines(dialogue).stream().anyMatch(line -> line.contains("愤怒")));
    }

    @Test
    void compilerNarrationUsesNormalToneWithoutLipSyncAndKeepsMouthClosed() {
        String dialogue = "旁白：夜色渐深。\n洛白：别跟过来。";
        String constraint = DramaStoryboardCompiler.spokenDialogueConstraint(dialogue);

        assertTrue(constraint.contains("旁白解说员以全片统一的旁白音色逐字念出：“夜色渐深。”，该音色不与任何角色混淆"));
        assertTrue(constraint.contains(DramaStoryboardCompiler.NARRATION_MOUTH_CLOSED));
        assertTrue(constraint.contains("角色“洛白”逐字说出：“别跟过来。”，口型与每个字严格同步"));
        assertFalse(constraint.contains("旁白\"逐字说出"));
        // 旁白句本身不要求口型同步；角色句仍要求。
        int narrationIdx = constraint.indexOf("旁白音色逐字念出");
        int lipOnNarration = constraint.indexOf("口型与每个字严格同步", narrationIdx);
        int characterIdx = constraint.indexOf("角色“洛白”");
        assertTrue(lipOnNarration < 0 || lipOnNarration > characterIdx);
        assertFalse(constraint.contains("对白表演："));
    }

    @Test
    void compilerIgnoresEmotionOnNarrationAndNormalizesAlias() {
        String dialogue = "画外音（愤怒）：风雨欲来。";
        List<DramaStoryboardCompiler.SpokenUtterance> utterances =
                DramaStoryboardCompiler.spokenUtterances(dialogue);
        assertEquals(1, utterances.size());
        assertEquals("旁白", utterances.get(0).speaker());
        assertNull(utterances.get(0).emotion());
        assertTrue(utterances.get(0).narration());

        String constraint = DramaStoryboardCompiler.spokenDialogueConstraint(dialogue);
        assertTrue(constraint.contains("旁白解说员以全片统一的旁白音色逐字念出：“风雨欲来。”"));
        assertFalse(constraint.contains("以愤怒的语气"));
        assertTrue(constraint.contains(DramaStoryboardCompiler.NARRATION_MOUTH_CLOSED));
        assertFalse(constraint.contains("对白表演："));
    }

    @Test
    void voiceOverMarkerIsAClosedSetDistinctFromEmotion() {
        assertTrue(DramaDialogueSpeakers.isVoiceOverMarker("画外音"));
        assertTrue(DramaDialogueSpeakers.isVoiceOverMarker("内心独白"));
        assertTrue(DramaDialogueSpeakers.isVoiceOverMarker("VO"));
        assertFalse(DramaDialogueSpeakers.isVoiceOverMarker("愤怒"));
        assertFalse(DramaDialogueSpeakers.isVoiceOverMarker(null));
        assertFalse(DramaDialogueSpeakers.isVoiceOverMarker(""));
    }

    @Test
    void characterVoiceOverParsesAsThatCharacterOffScreen() {
        // 「张总（画外音）：难道是他？」——说话人是真实角色，用张总音色在画面外念出。
        String dialogue = "张总（画外音）：难道是他？";
        List<DramaStoryboardCompiler.SpokenUtterance> utterances =
                DramaStoryboardCompiler.spokenUtterances(dialogue);

        assertEquals(1, utterances.size());
        assertEquals("张总", utterances.get(0).speaker());
        assertTrue(utterances.get(0).voiceOver());
        assertFalse(utterances.get(0).narration());
        assertNull(utterances.get(0).emotion());
        assertEquals(List.of("难道是他？"), DramaStoryboardCompiler.captionLines(dialogue));
    }

    @Test
    void characterVoiceOverRendersWithThatCharacterVoiceAndMouthClosed() {
        String dialogue = "张总（画外音）：难道是他？";
        String constraint = DramaStoryboardCompiler.spokenDialogueConstraint(dialogue);

        assertTrue(constraint.contains(
                "角色“张总”的画外音（使用张总的音色，自画面外传来）逐字念出：“难道是他？”"));
        assertTrue(constraint.contains(DramaStoryboardCompiler.NARRATION_MOUTH_CLOSED));
        // 画外音不开口，不要求口型同步，也没有情绪表演段。
        assertFalse(constraint.contains("口型与每个字严格同步"));
        assertFalse(constraint.contains("对白表演："));
        assertFalse(constraint.contains("旁白解说员"));
    }

    @Test
    void voiceOverDoesNotLeakIntoCharacterLineRendering() {
        // 画外音镜与角色开口镜混排：各自的约束互不串扰。
        String dialogue = "张总（画外音）：难道是他？\n李四：是我。";
        String constraint = DramaStoryboardCompiler.spokenDialogueConstraint(dialogue);

        assertTrue(constraint.contains("角色“张总”的画外音（使用张总的音色，自画面外传来）逐字念出：“难道是他？”"));
        assertTrue(constraint.contains("角色“李四”逐字说出：“是我。”，口型与每个字严格同步"));
        assertTrue(constraint.contains(DramaStoryboardCompiler.NARRATION_MOUTH_CLOSED));
    }

    @Test
    void neutralDialogueKeepsLegacyBehavior() {
        String dialogue = "局长：洛老弟，别睡了。";
        String constraint = DramaStoryboardCompiler.spokenDialogueConstraint(dialogue);
        assertTrue(constraint.contains("角色“局长”逐字说出：“洛老弟，别睡了。”，口型与每个字严格同步"));
        assertFalse(constraint.contains("以愤怒"));
        assertFalse(constraint.contains("对白表演："));
        assertFalse(constraint.contains(DramaStoryboardCompiler.NARRATION_MOUTH_CLOSED));
        assertTrue(constraint.contains(DramaStoryboardCompiler.DIALOGUE_EXECUTION_TAIL));
    }
}
