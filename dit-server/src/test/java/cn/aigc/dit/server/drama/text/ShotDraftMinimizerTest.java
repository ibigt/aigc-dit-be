package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.DramaShotBeats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ShotDraftMinimizerTest {

    @Test
    void mergesAdjacentSameSubjectActionBeatsUpToFifteenSeconds() {
        ShotDraftList list = new ShotDraftList();
        list.setShots(List.of(
                shot(1, 1, 5, "进入", "洛白走进办公室。", "", List.of("洛白"), List.of()),
                shot(1, 2, 5, "拿照片", "洛白拿起照片。", "", List.of("洛白"), List.of("照片")),
                shot(1, 3, 5, "推近", "镜头推近洛白。", "", List.of("洛白"), List.of("照片"))));
        list.getShots().get(1).setContinuityIn("洛白站在桌前");
        list.getShots().get(1).setContinuityOut("洛白手持照片");
        list.getShots().get(2).setContinuityIn("洛白手持照片");

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(1, minimized.getShots().size());
        ShotDraftList.ShotDraft merged = minimized.getShots().get(0);
        assertEquals(15, merged.getDurationSec());
        assertEquals(1, merged.getIndex());
        assertEquals("洛白走进办公室。洛白拿起照片。镜头推近洛白。", merged.getNarrativeGoal());
        assertEquals(List.of("洛白"), merged.getCharacterNames());
        assertEquals(List.of("照片"), merged.getPropNames());
    }

    @Test
    void keepsDraftsSeparateWhenMergedDurationWouldExceedFifteen() {
        ShotDraftList list = new ShotDraftList();
        list.setShots(List.of(
                shot(1, 1, 8, "进入", "洛白走进办公室。", "", List.of("洛白"), List.of()),
                shot(1, 2, 8, "拿照片", "洛白拿起照片。", "", List.of("洛白"), List.of("照片"))));
        list.getShots().get(1).setContinuityIn("洛白站在桌前");

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size());
    }

    @Test
    void keepsAdjacentDraftsSeparateWhenVisibleContinuityDoesNotMatch() {
        ShotDraftList list = new ShotDraftList();
        list.setShots(List.of(
                shot(1, 1, 5, "动作一", "洛白把门关上。", "", List.of("洛白"), List.of()),
                shot(1, 2, 5, "动作二", "洛白从打开的门外进入。", "", List.of("洛白"), List.of())));
        list.getShots().get(0).setContinuityOut("门已关闭");
        list.getShots().get(1).setContinuityIn("门保持打开");

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size());
    }

    @Test
    void keepsDraftsSeparateWhenContinuityMetadataIsIncomplete() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft first = shot(1, 1, 5, "动作一", "洛白走到门前。", "", List.of("洛白"), List.of());
        ShotDraftList.ShotDraft second = shot(1, 2, 5, "动作二", "洛白进入房间。", "", List.of("洛白"), List.of());
        first.setContinuityOut(null);
        list.setShots(List.of(first, second));

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size());
    }

    @Test
    void keepsDraftsSeparateWhenCharacterSetsDiffer() {
        ShotDraftList list = new ShotDraftList();
        list.setShots(List.of(
                shot(1, 1, 5, "贴照片", "夏安贴照片。", "", List.of("夏安"), List.of("白板")),
                shot(1, 2, 5, "盖脸", "洛白用书盖脸。", "", List.of("洛白"), List.of())));
        list.getShots().get(1).setContinuityIn(list.getShots().get(0).getContinuityOut());

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size());
    }

    @Test
    void mergesAdjacentDialogueTurnsEvenWhenContinuityLabelsDiffer() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft first = shot(
                1, 1, 5, "叫人", "局长走近。", "局长：洛老弟，别睡了。", List.of("局长"), List.of());
        ShotDraftList.ShotDraft second = shot(
                1, 2, 5, "回应", "洛白抬头。", "洛白：来了。", List.of("洛白"), List.of());
        first.setContinuityOut("局长站定");
        second.setContinuityIn("洛白抬头");
        list.setShots(List.of(first, second));

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(1, minimized.getShots().size());
        assertEquals("局长：洛老弟，别睡了。\n洛白：来了。", minimized.getShots().get(0).getDialogue());
    }

    @Test
    void keepsDialogueInDifferentLocationsSeparate() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft first = shot(
                1, 1, 5, "室内", "局长说话。", "局长：跟我来。", List.of("局长"), List.of());
        ShotDraftList.ShotDraft second = shot(
                1, 2, 5, "室外", "洛白回应。", "洛白：好。", List.of("洛白"), List.of());
        second.setLocationName("街巷");
        list.setShots(List.of(first, second));

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size());
    }

    @Test
    void keepsDialogueTurnsSeparateWhenMergedDurationWouldExceedFifteen() {
        ShotDraftList list = new ShotDraftList();
        list.setShots(List.of(
                shot(1, 1, 8, "叫人", "局长走近。", "局长：洛老弟，别睡了。", List.of("局长"), List.of()),
                shot(1, 2, 8, "回应", "洛白抬头。", "洛白：来了。", List.of("洛白"), List.of())));
        list.getShots().get(1).setContinuityIn(list.getShots().get(0).getContinuityOut());

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size());
    }

    @Test
    void keepsDialogueTurnsSeparateWhenMergedDialogueWouldNotFitCombinedDuration() {
        ShotDraftList list = new ShotDraftList();
        // 两镜单独都念得完（各 31 口播字→8s），但合计 62 口播字要 16s，超过合并后的 15s。
        ShotDraftList.ShotDraft first = shot(
                1, 1, 9, "质问", "局长走近。",
                "局长：今天我们必须把这件事情查个水落石出，绝不能让任何人再逍遥法外跑了", List.of("局长"), List.of());
        ShotDraftList.ShotDraft second = shot(
                1, 2, 8, "回应", "洛白抬头。",
                "洛白：放心，我已经锁定了那三条关键线索，今晚之前一定把全部证据都拿到手里", List.of("洛白"), List.of());
        list.setShots(List.of(first, second));

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size(),
                "合并后台词念不完的对谈不得并成一镜，否则台词会在 15 秒封顶处被切掉");
    }

    @Test
    void mergesDialogueTurnsWhenPunctuationWasTheOnlyOverflow() {
        ShotDraftList list = new ShotDraftList();
        // 口播字 29 + 29 = 58，合并后 15 秒念得完；连标点一起数成 61 字会错判念不完而拒绝合并。
        ShotDraftList.ShotDraft first = shot(
                1, 1, 8, "质问", "局长走近。",
                "局长：今天我们必须把这件事情查个水落石出，不能让任何人再逍遥法外了", List.of("局长"), List.of());
        ShotDraftList.ShotDraft second = shot(
                1, 2, 7, "回应", "洛白抬头。",
                "洛白：放心，我已经锁定了那三条关键线索，今晚之前一定把全部证据拿到手", List.of("洛白"), List.of());
        list.setShots(List.of(first, second));

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(1, minimized.getShots().size(),
                "标点不发音，不得因标点把口播字数顶过预算而拒绝本可合并的对谈");
    }

    @Test
    void mergedDraftUsesLastShotsBlankContinuityOut() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft first = shot(1, 1, 5, "动作一", "洛白拿起照片。", "", List.of("洛白"), List.of("照片"));
        ShotDraftList.ShotDraft second = shot(1, 2, 5, "动作二", "洛白放下照片。", "", List.of("洛白"), List.of("照片"));
        second.setContinuityIn(first.getContinuityOut());
        second.setContinuityOut("");
        list.setShots(List.of(first, second));

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(1, minimized.getShots().size());
        assertEquals("", minimized.getShots().get(0).getContinuityOut());
    }

    @Test
    void mergesBeatShotsWithoutJoiningActionsIntoOneSentence() {
        ShotDraftList list = new ShotDraftList();
        ShotDraftList.ShotDraft first = shot(1, 1, 5, "进门", "进门问责。", "", List.of("局长"), List.of("档案"));
        ShotDraftList.ShotDraft second = shot(1, 2, 5, "摔档案", "摔下档案。", "", List.of("局长"), List.of("档案"));
        first.setShotBeats(beats("局长在门口。", beat("中景，跟拍", "局长推门进入。", "门开", "")));
        second.setShotBeats(beats("局长已进门。", beat("近景，下摇", "局长把档案摔在桌上。", "接推门进入", "")));
        second.setContinuityIn(first.getContinuityOut());
        list.setShots(List.of(first, second));

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(1, minimized.getShots().size());
        ShotDraftList.ShotDraft merged = minimized.getShots().get(0);
        assertEquals("进门问责。", merged.getNarrativeGoal());
        assertEquals(2, merged.getShotBeats().getBeats().size());
        assertEquals("局长推门进入。", merged.getShotBeats().getBeats().get(0).getAction());
        assertEquals("局长把档案摔在桌上。", merged.getShotBeats().getBeats().get(1).getAction());
        assertFalse(merged.getNarrativeGoal().contains("局长把档案摔在桌上"));
    }

    @Test
    void splitsOverlongDialogueAtBeatBoundaries() {
        String line = "局长：" + "零一二三四五六七八九".repeat(3);
        ShotDraftList.ShotDraft shot = shot(1, 1, 15, "问责", "局长问责。", "", List.of("局长"), List.of());
        shot.setShotBeats(beats("局长在门口。",
                beat("中景，跟拍", "局长推门进入。", "门开", line),
                beat("近景，下摇", "局长把档案摔在桌上。", "接推门进入", line),
                beat("特写，静止", "局长盯着洛白。", "接档案落桌", line)));
        shot.setDialogue(String.join("\n", List.of(line, line, line)));
        ShotDraftList list = new ShotDraftList();
        list.setShots(new java.util.ArrayList<>(List.of(shot)));

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size());
        ShotDraftList.ShotDraft first = minimized.getShots().get(0);
        ShotDraftList.ShotDraft second = minimized.getShots().get(1);
        assertEquals(2, first.getShotBeats().getBeats().size());
        assertEquals(1, second.getShotBeats().getBeats().size());
        assertEquals("局长推门进入。", first.getNarrativeGoal());
        assertEquals("局长盯着洛白。", second.getNarrativeGoal());
        assertEquals(line + "\n" + line, first.getDialogue());
        assertEquals(line, second.getDialogue());
        assertFalse(first.getNarrativeGoal().contains("摔"));
    }

    private static DramaShotBeats beats(String blocking, DramaShotBeats.Beat... items) {
        DramaShotBeats beats = new DramaShotBeats();
        beats.setBlocking(blocking);
        beats.setBeats(new java.util.ArrayList<>(List.of(items)));
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

    private static ShotDraftList.ShotDraft shot(
            int chapter, int index, int duration, String title, String action, String dialogue,
            List<String> characters, List<String> props) {
        ShotDraftList.ShotDraft shot = new ShotDraftList.ShotDraft();
        shot.setChapterIndex(chapter);
        shot.setIndex(index);
        shot.setDurationSec(duration);
        shot.setTitle(title);
        shot.setNarrativeGoal(action);
        shot.setDialogue(dialogue);
        shot.setScene("办公室日戏");
        shot.setLocationName("办公室");
        shot.setContinuityIn("门关闭");
        shot.setContinuityOut("洛白站在桌前");
        shot.setCharacterNames(characters);
        shot.setPropNames(props);
        shot.setCastCount(characters.size());
        return shot;
    }
}
