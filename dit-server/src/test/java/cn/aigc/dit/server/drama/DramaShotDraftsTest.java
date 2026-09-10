package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(shots.stream().anyMatch(item -> item.getTitle().contains("开场") || "交代空间与人物关系".equals(item.getNarrativeGoal())));
        assertTrue(shots.stream().anyMatch(item -> item.getTitle().contains("局长") || item.getNarrativeGoal().contains("局长")));
        assertTrue(shots.stream().anyMatch(item -> item.getTitle().contains("怀表") || item.getNarrativeGoal().contains("道具")));
        assertTrue(shots.stream().allMatch(item -> item.getCharacterNames().contains("洛白")));
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
}
