package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaShotDraftsTest {

    @Test
    void firstSceneProducesFourToEightShotsCoveringBeats() {
        ShotDraftList list = DramaShotDrafts.fromScript(detectiveScene(), List.of("洛白", "夏安", "局长", "古铜怀表", "幻梦侦探社"), 5);
        assertEquals(1, list.getChapters().size());
        assertEquals(1, list.getChapters().get(0).getSceneIndex());
        List<ShotDraftList.ShotDraft> shots = list.getShots();
        assertTrue(shots.size() >= DramaContract.FIRST_SCENE_MIN_SHOTS);
        assertTrue(shots.size() <= DramaContract.FIRST_SCENE_MAX_SHOTS);
        assertTrue(shots.stream().allMatch(item -> DramaContract.DURATIONS.contains(item.getDurationSec())));
        assertTrue(shots.stream().allMatch(item -> item.getCastCount() == item.getCharacterNames().size()));
        assertTrue(shots.stream().anyMatch(item -> item.getTitle().contains("开场")));
        assertTrue(shots.stream().anyMatch(item -> item.getTitle().contains("夏安")));
        assertTrue(shots.stream().anyMatch(item -> item.getTitle().contains("局长")));
        assertTrue(shots.stream().anyMatch(item -> item.getTitle().contains("怀表")));
        assertTrue(shots.stream().anyMatch(item -> item.getCharacterNames().contains("洛白")));
        assertTrue(shots.stream().anyMatch(item -> item.getCharacterNames().contains("夏安")));
        assertTrue(shots.stream().anyMatch(item -> item.getCharacterNames().contains("局长")));
        assertFalse(shots.get(0).getCharacterNames().contains("局长"));
        assertTrue(shots.get(0).getCharacterNames().containsAll(List.of("夏安", "洛白")));
        assertEquals(List.of("夏安", "洛白"), shots.get(0).getCharacterNames());
        assertTrue(shots.stream().noneMatch(item -> plotSpeak(item.getNarrativeGoal())));
        assertTrue(shots.stream().noneMatch(item -> plotSpeakHook(item.getContinuityIn()) || plotSpeakHook(item.getContinuityOut())));
        assertTrue(shots.get(0).getNarrativeGoal().startsWith("全景"));
        assertTrue(shots.get(0).getNarrativeGoal().contains("风扇") || shots.get(0).getNarrativeGoal().contains("夏安"));
        assertTrue(shots.stream().anyMatch(item -> item.getNarrativeGoal().contains("夏安") && item.getNarrativeGoal().contains("白板")));
        assertTrue(shots.stream().anyMatch(item -> item.getNarrativeGoal().contains("局长")));
        assertTrue(shots.stream().anyMatch(item -> item.getNarrativeGoal().contains("怀表") && item.getNarrativeGoal().startsWith("特写")));
        assertTrue(shots.stream().allMatch(item -> "幻梦侦探社 日 内".equals(item.getScene())));
        assertTrue(shots.stream().noneMatch(item -> item.getPropNames().contains("古铜怀表")
                && item.getNarrativeGoal() != null && !item.getNarrativeGoal().contains("怀表")));
        assertTrue(shots.stream().anyMatch(item -> item.getTitle().contains("怀表")
                && item.getPropNames().contains("古铜怀表")
                && item.getCharacterNames().equals(List.of("洛白"))));
        assertTrue(shots.stream().map(ShotDraftList.ShotDraft::getDurationSec).distinct().count() > 1,
                "规则拆镜应按对白/动作估不同时长");
        for (int i = 1; i < shots.size(); i++) {
            assertEquals(shots.get(i - 1).getContinuityOut(), shots.get(i).getContinuityIn());
        }
    }

    @Test
    void laterSceneKeepsAtLeastTwoShots() {
        ScriptDoc doc = new ScriptDoc();
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(2);
        scene.setHeading("市中心医院ICU 夜 内");
        scene.setCharacters(List.of("洛白", "徐医生"));
        scene.setLocation("市中心医院ICU");
        scene.setAction("洛白入梦。光点抽离。");
        doc.setScenes(List.of(scene));

        ShotDraftList list = DramaShotDrafts.fromScript(doc, List.of("洛白", "徐医生", "市中心医院ICU"));
        assertTrue(list.getShots().size() >= DramaContract.MIN_SHOTS_PER_CHAPTER);
        assertTrue(list.getShots().size() <= DramaContract.MAX_SHOTS_PER_CHAPTER);
        assertEquals(Integer.valueOf(2), list.getShots().get(0).getChapterIndex());
        assertTrue(list.getShots().stream().noneMatch(item -> plotSpeak(item.getNarrativeGoal())));
        assertTrue(list.getShots().get(0).getNarrativeGoal().contains("入梦")
                || list.getShots().get(0).getNarrativeGoal().contains("光点"));
        assertEquals("市中心医院ICU 夜 内", list.getShots().get(0).getScene());
        assertTrue(list.getShots().get(0).getCharacterNames().contains("洛白"));
        assertFalse(list.getShots().get(0).getCharacterNames().contains("徐医生"));
    }

    @Test
    void estimateDurationGrowsWithDialogue() {
        int shortShot = DramaShotDrafts.estimateDuration(null, "特写。", 5);
        int longShot = DramaShotDrafts.estimateDuration(
                "局长：洛老弟别睡了，这案子今晚必须结，档案都在桌上。",
                "中景。局长摔档案，洛白坐直。",
                5);
        assertTrue(shortShot >= 5 && shortShot <= 8);
        assertTrue(longShot > shortShot);
        assertTrue(longShot <= 15);
    }

    private static ScriptDoc detectiveScene() {
        ScriptDoc doc = new ScriptDoc();
        doc.setTitle("幻梦侦探社");
        ScriptDoc.Scene scene = new ScriptDoc.Scene();
        scene.setIndex(1);
        scene.setHeading("幻梦侦探社 日 内");
        scene.setCharacters(List.of("洛白", "夏安", "局长"));
        scene.setProps(List.of("古铜怀表"));
        scene.setLocation("幻梦侦探社");
        scene.setAction("风扇转动。夏安在白板贴照片。洛白用书盖脸。局长摔档案。洛白掏怀表。");
        ScriptDoc.Dialogue line = new ScriptDoc.Dialogue();
        line.setSpeaker("局长");
        line.setLine("洛老弟，别睡了。");
        scene.setDialogue(List.of(line));
        doc.setScenes(List.of(scene));
        return doc;
    }

    private static boolean plotSpeak(String goal) {
        if (goal == null || goal.isBlank()) {
            return true;
        }
        return goal.contains("交代") || goal.contains("推进对白") || goal.contains("关键道具出场")
                || goal.contains("收住本场");
    }

    private static boolean plotSpeakHook(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return value.contains("环境立住") || value.contains("局长将至") || value.contains("白板线索")
                || value.contains("冲突立住") || value.contains("对白落点") || value.contains("动作落点")
                || value.contains("切下一场") || value.contains("洛白被叫醒") || value.contains("怀表特写");
    }
}
