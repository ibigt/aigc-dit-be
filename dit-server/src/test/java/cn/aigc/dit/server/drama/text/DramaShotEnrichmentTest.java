package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.DramaShotBeats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaShotEnrichmentTest {

    private static final Set<String> ASSETS =
            Set.of("洛白", "夏安", "局长", "古铜怀表", "幻梦侦探社");

    @Test
    void mergesVisualEnrichmentWhileKeepingAnchorsAndOtherFields() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = enrich(
                1, 2,
                "中景，手持跟拍。洛白推开档案室的门，冷白灯管在头顶轻晃，浮尘掠过光柱，他停步回身望向办公桌。",
                "档案室一隅，铁柜森然，牛皮档案摞成斜塔，灯管嗡鸣，墙皮斑驳。");

        int applied = DramaShotEnrichment.merge(list, enriched, ASSETS);

        assertEquals(2, applied);
        assertEquals("中景，手持跟拍。洛白推开档案室的门，冷白灯管在头顶轻晃，浮尘掠过光柱，他停步回身望向办公桌。",
                shot.getNarrativeGoal());
        assertEquals("档案室一隅，铁柜森然，牛皮档案摞成斜塔，灯管嗡鸣，墙皮斑驳。", shot.getScene());
        assertEquals("局长（愤怒）：你给我站住！", shot.getDialogue());
        assertEquals(8, shot.getDurationSec());
        assertEquals(List.of("洛白", "局长"), shot.getCharacterNames());
    }

    @Test
    void keepsOriginalWhenMentionedCharacterDropped() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = enrich(
                1, 2,
                "中景，缓慢推近。冷白灯管在头顶轻晃，浮尘掠过光柱，档案室的门被推开。",
                "档案室一隅，铁柜森然，灯管嗡鸣。");

        assertEquals(0, DramaShotEnrichment.merge(list, enriched, ASSETS));
        assertEquals("中景。洛白推开档案室的门。", shot.getNarrativeGoal());
        assertEquals("档案室日戏", shot.getScene());
    }

    @Test
    void keepsOriginalWhenForeignAssetNameIntroduced() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = enrich(
                1, 2,
                "中景。洛白推开档案室的门，夏安靠在门边回头。",
                "档案室一隅，铁柜森然，灯管嗡鸣。");

        assertEquals(0, DramaShotEnrichment.merge(list, enriched, ASSETS));
        assertEquals("中景。洛白推开档案室的门。", shot.getNarrativeGoal());
    }

    @Test
    void keepsOriginalWhenDialogueLeakedIntoNarrative() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = enrich(
                1, 2,
                "中景。洛白推开档案室的门，冷白灯管轻晃，局长怒吼你给我站住！",
                "档案室一隅，铁柜森然，灯管嗡鸣。");

        assertEquals(0, DramaShotEnrichment.merge(list, enriched, ASSETS));
        assertEquals("中景。洛白推开档案室的门。", shot.getNarrativeGoal());
    }

    @Test
    void keepsOriginalWhenOverLength() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        String tooLongNarrative = "洛白推开档案室的门" + "，灯管轻晃浮尘浮动".repeat(22);
        String tooLongScene = "档案室" + "，铁柜森然档案成摞".repeat(15);

        assertEquals(0, DramaShotEnrichment.merge(list,
                enrich(1, 2, tooLongNarrative, "档案室一隅，铁柜森然。"), ASSETS));
        assertEquals(0, DramaShotEnrichment.merge(list,
                enrich(1, 2, "中景。洛白推开档案室的门，冷光洒落。", tooLongScene), ASSETS));
        assertEquals("中景。洛白推开档案室的门。", shot.getNarrativeGoal());
        assertEquals("档案室日戏", shot.getScene());
    }

    @Test
    void rejectsWhenSceneHasConnectionStateAndNarrativeHasInsertAction() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = new DramaShotEnrichment.ShotEnrichList();
        DramaShotEnrichment.ShotEnrich item = new DramaShotEnrichment.ShotEnrich();
        item.setChapterIndex(1);
        item.setIndex(2);
        // scene 写「线缆盘绕」+ narrative_goal 写「插入接口」是互斥态，模型会把 U 盘画成有线连接设备。
        item.setScene("日光斜照工位，桌角线缆盘绕，U盘金属壳反射冷光。");
        item.setNarrativeGoal("中景。林奇伸手自桌面拿起U盘插入接口。");
        enriched.setShots(new ArrayList<>(List.of(item)));

        assertEquals(0, DramaShotEnrichment.merge(list, enriched, ASSETS));
        assertEquals("中景。洛白推开档案室的门。", shot.getNarrativeGoal(),
                "互斥态描述必须整镜回退原稿，否则视频模型会把 U 盘画成带线缆或插入后消失");
    }

    @Test
    void acceptsSceneWithConnectionStateWhenNoInsertAction() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = new DramaShotEnrichment.ShotEnrichList();
        DramaShotEnrichment.ShotEnrich item = new DramaShotEnrichment.ShotEnrich();
        item.setChapterIndex(1);
        item.setIndex(2);
        // scene 单独写「线缆盘绕」合法：本镜不涉及插入动作，描述桌面陈设没有问题。
        item.setScene("日光斜照工位，桌角线缆盘绕，U盘金属壳反射冷光。");
        item.setNarrativeGoal("中景。洛白低头看屏幕。");
        enriched.setShots(new ArrayList<>(List.of(item)));

        assertEquals(2, DramaShotEnrichment.merge(list, enriched, ASSETS),
                "仅 scene 出现连接词、narrative_goal 无插入动作时不应当误伤");
    }

    @Test
    void acceptsNarrativeWithInsertActionWhenSceneHasNoConnectionState() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = new DramaShotEnrichment.ShotEnrichList();
        DramaShotEnrichment.ShotEnrich item = new DramaShotEnrichment.ShotEnrich();
        item.setChapterIndex(1);
        item.setIndex(2);
        // narrative_goal 单独写「插入」合法：动作完整保留，scene 描述空间光线不混入连接词。
        item.setScene("日光斜照工位，挂钟卡在11点59分，U盘金属壳反射冷光。");
        item.setNarrativeGoal("中景。洛白伸手自桌面拿起U盘插入主机。");
        enriched.setShots(new ArrayList<>(List.of(item)));

        assertEquals(2, DramaShotEnrichment.merge(list, enriched, ASSETS),
                "动作完整保留是硬合同，不能因为 mutability 丢失");
    }

    @Test
    void ignoresEntriesWithUnknownOrBlankKeys() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        assertEquals(0, DramaShotEnrichment.merge(list,
                enrich(9, 1, "中景。洛白推门，冷光洒落。", "铁柜森然。"), ASSETS));
        assertEquals(0, DramaShotEnrichment.merge(list,
                enrich(1, 2, "  ", "铁柜森然。"), ASSETS));
        assertEquals(0, DramaShotEnrichment.merge(list,
                enrich(1, 2, "中景。洛白推门，冷光洒落。", ""), ASSETS));
        assertEquals("中景。洛白推开档案室的门。", shot.getNarrativeGoal());
    }

    @Test
    void appliesPerShotWhenOnlyPartOfBatchIsValid() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft first = shot();
        ShotDraftList.ShotDraft second = shot();
        second.setChapterIndex(1);
        second.setIndex(3);
        second.setNarrativeGoal("特写。古铜怀表停在三点。");
        list.setShots(new ArrayList<>(List.of(first, second)));

        DramaShotEnrichment.ShotEnrichList enriched = new DramaShotEnrichment.ShotEnrichList();
        DramaShotEnrichment.ShotEnrich good = enrich(1, 2,
                "中景，手持跟拍。洛白推开档案室的门，冷白灯管轻晃，浮尘掠过光柱。",
                "档案室一隅，铁柜森然，灯管嗡鸣。").getShots().get(0);
        DramaShotEnrichment.ShotEnrich bad = enrich(1, 3,
                "特写。怀表停在三点。", "铁柜森然。").getShots().get(0);
        enriched.setShots(new ArrayList<>(List.of(good, bad)));

        assertEquals(2, DramaShotEnrichment.merge(list, enriched, ASSETS));
        assertEquals("中景，手持跟拍。洛白推开档案室的门，冷白灯管轻晃，浮尘掠过光柱。",
                first.getNarrativeGoal());
        // 原稿点名「古铜怀表」，扩写丢了锚点名 → 整镜回退。
        assertEquals("特写。古铜怀表停在三点。", second.getNarrativeGoal());
    }

    @Test
    void storesCameraAndAmbianceSeparatelyFromAction() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = enrich(
                1, 2,
                "洛白推开档案室的门，停步回身望向办公桌。",
                "档案室一隅，铁柜森然，灯管嗡鸣。");
        enriched.getShots().get(0).setCamera("中景，手持跟拍");
        enriched.getShots().get(0).setAmbiance("灯管低频嗡鸣");

        assertEquals(4, DramaShotEnrichment.merge(list, enriched, ASSETS));
        assertEquals("洛白推开档案室的门，停步回身望向办公桌。", shot.getNarrativeGoal());
        assertEquals("中景，手持跟拍", shot.getCamera());
        assertEquals("灯管低频嗡鸣", shot.getAmbiance());
    }

    @Test
    void rejectsEditVerbsThatLookLikeAVideoEditTask() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = enrich(
                1, 2,
                "把洛白脸上的书改成盖严。",
                "档案室一隅，铁柜森然，灯管嗡鸣。");

        assertEquals(0, DramaShotEnrichment.merge(list, enriched, ASSETS));
        assertEquals("中景。洛白推开档案室的门。", shot.getNarrativeGoal());
    }

    @Test
    void keepsEachBeatInsteadOfCollapsingThemIntoNarrativeGoal() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = shot();
        shot.setNarrativeGoal("局长进门摔档案并问责。");
        shot.setShotBeats(beats("局长在门口。",
                beat("中景，跟拍", "局长推门进入。", "门开", ""),
                beat("近景，下摇", "局长把档案摔在桌上。", "接推门进入", ""),
                beat("特写，静止", "局长盯着洛白。", "接档案落桌", "局长（愤怒）：你给我站住！")));
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList enriched = new DramaShotEnrichment.ShotEnrichList();
        DramaShotEnrichment.ShotEnrich item = new DramaShotEnrichment.ShotEnrich();
        item.setChapterIndex(1);
        item.setIndex(2);
        item.setNarrativeGoal("局长推门、摔档案、盯着洛白，三拍收成一句长动作。");
        item.setScene("日光斜切进局长办公室，桌案堆着牛皮档案。");
        item.setBlocking("局长停在门口，洛白坐在画面左侧。");
        item.setBeats(List.of(
                beatEnrich("中景，跟拍", "局长推门进入，门扇撞上墙。", "门开"),
                beatEnrich("近景，下摇", "局长把档案摔在桌上，纸页散开。", "接推门进入"),
                beatEnrich("特写，静止", "局长盯着洛白，下颌绷紧。", "接档案落桌")));
        enriched.setShots(new ArrayList<>(List.of(item)));

        assertTrue(DramaShotEnrichment.merge(list, enriched, ASSETS) > 0);
        assertEquals("局长进门摔档案并问责。", shot.getNarrativeGoal());
        assertEquals("局长推门进入，门扇撞上墙。", shot.getShotBeats().getBeats().get(0).getAction());
        assertEquals("局长把档案摔在桌上，纸页散开。", shot.getShotBeats().getBeats().get(1).getAction());
        assertEquals("局长盯着洛白，下颌绷紧。", shot.getShotBeats().getBeats().get(2).getAction());
        assertEquals("局长（愤怒）：你给我站住！", shot.getShotBeats().getBeats().get(2).getDialogue());
        assertEquals("局长（愤怒）：你给我站住！", shot.getDialogue());
        assertFalse(shot.getNarrativeGoal().contains("门扇撞上墙"));
    }

    @Test
    void rollsBackWholeShotWhenBeatDropsNameOrMixesDialogueIntoAction() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft shot = beatShot();
        list.setShots(new ArrayList<>(List.of(shot)));

        DramaShotEnrichment.ShotEnrichList dropped = beatEnrichment(
                beatEnrich("中景", "门被推开。", "门开"),
                beatEnrich("近景", "档案摔在桌上。", "接推门"),
                beatEnrich("特写", "盯着对面。", "接落桌"));
        assertEquals(0, DramaShotEnrichment.merge(list, dropped, ASSETS));
        assertEquals("局长推门进入。", shot.getShotBeats().getBeats().get(0).getAction());

        DramaShotEnrichment.ShotEnrichList leaked = beatEnrichment(
                beatEnrich("中景，跟拍", "局长推门进入。", "门开"),
                beatEnrich("近景，下摇", "局长把档案摔在桌上。", "接推门进入"),
                beatEnrich("特写，静止", "局长盯着洛白，吼你给我站住！", "接档案落桌"));
        assertEquals(0, DramaShotEnrichment.merge(list, leaked, ASSETS));
        assertEquals("局长盯着洛白。", shot.getShotBeats().getBeats().get(2).getAction());
        assertEquals("局长进门摔档案并问责。", shot.getNarrativeGoal());
    }

    private static ShotDraftList.ShotDraft beatShot() {
        ShotDraftList.ShotDraft shot = shot();
        shot.setNarrativeGoal("局长进门摔档案并问责。");
        shot.setDialogue("局长（愤怒）：你给我站住！");
        shot.setShotBeats(beats("局长在门口。",
                beat("中景，跟拍", "局长推门进入。", "门开", ""),
                beat("近景，下摇", "局长把档案摔在桌上。", "接推门进入", ""),
                beat("特写，静止", "局长盯着洛白。", "接档案落桌", "局长（愤怒）：你给我站住！")));
        return shot;
    }

    private static DramaShotBeats beats(String blocking, DramaShotBeats.Beat... items) {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking(blocking);
        beats.setBeats(new ArrayList<>(List.of(items)));
        return beats;
    }

    private static DramaShotBeats.Beat beat(String camera, String action, String continuity, String dialogue) {
        DramaShotBeats.Beat beat = new DramaShotBeats.Beat();
        beat.setCamera(camera);
        beat.setAction(action);
        beat.setContinuity(continuity);
        beat.setDialogue(dialogue);
        return beat;
    }

    private static DramaShotEnrichment.BeatEnrich beatEnrich(String camera, String action, String continuity) {
        DramaShotEnrichment.BeatEnrich beat = new DramaShotEnrichment.BeatEnrich();
        beat.setCamera(camera);
        beat.setAction(action);
        beat.setContinuity(continuity);
        return beat;
    }

    private static DramaShotEnrichment.ShotEnrichList beatEnrichment(DramaShotEnrichment.BeatEnrich... items) {
        DramaShotEnrichment.ShotEnrichList enriched = new DramaShotEnrichment.ShotEnrichList();
        DramaShotEnrichment.ShotEnrich item = new DramaShotEnrichment.ShotEnrich();
        item.setChapterIndex(1);
        item.setIndex(2);
        item.setScene("日光斜切进局长办公室，桌案堆着牛皮档案。");
        item.setBeats(new ArrayList<>(List.of(items)));
        enriched.setShots(new ArrayList<>(List.of(item)));
        return enriched;
    }

    private static ShotDraftList.ShotDraft shot() {
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setChapterIndex(1);
        shot.setIndex(2);
        shot.setTitle("对峙");
        shot.setDurationSec(8);
        shot.setNarrativeGoal("中景。洛白推开档案室的门。");
        shot.setScene("档案室日戏");
        shot.setDialogue("局长（愤怒）：你给我站住！");
        shot.setCharacterNames(new ArrayList<>(List.of("洛白", "局长")));
        shot.setPropNames(new ArrayList<>());
        shot.setLocationName("幻梦侦探社");
        shot.setCastCount(2);
        return shot;
    }

    private static DramaShotEnrichment.ShotEnrichList enrich(
            int chapterIndex, int index, String narrativeGoal, String scene) {
        DramaShotEnrichment.ShotEnrichList list = new DramaShotEnrichment.ShotEnrichList();
        DramaShotEnrichment.ShotEnrich item = new DramaShotEnrichment.ShotEnrich();
        item.setChapterIndex(chapterIndex);
        item.setIndex(index);
        item.setNarrativeGoal(narrativeGoal);
        item.setScene(scene);
        list.setShots(new ArrayList<>(List.of(item)));
        return list;
    }
}
