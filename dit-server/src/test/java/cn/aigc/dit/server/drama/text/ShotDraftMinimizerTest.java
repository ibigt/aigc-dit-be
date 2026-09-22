package cn.aigc.dit.server.drama.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void keepsDraftsSeparateWhenBothHaveDialogue() {
        ShotDraftList list = new ShotDraftList();
        list.setShots(List.of(
                shot(1, 1, 5, "叫人", "局长走近。", "局长：洛老弟，别睡了。", List.of("局长"), List.of()),
                shot(1, 2, 5, "回应", "洛白抬头。", "洛白：来了。", List.of("洛白"), List.of())));
        list.getShots().get(0).setCharacterNames(List.of("局长", "洛白"));
        list.getShots().get(1).setCharacterNames(List.of("局长", "洛白"));
        list.getShots().get(1).setContinuityIn(list.getShots().get(0).getContinuityOut());

        ShotDraftList minimized = ShotDraftMinimizer.minimize(list, 15);

        assertEquals(2, minimized.getShots().size());
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
