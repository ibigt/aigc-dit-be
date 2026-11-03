package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DramaFilmModesTest {

    @Test
    void totalKeptSecRoundsTrimmedSpans() {
        assertEquals(10, DramaFilmModes.totalKeptSec(List.of(
                new DramaFilmModes.ClipSpan(0, 0.2, 5),
                new DramaFilmModes.ClipSpan(0.2, 0, 5))));
        assertEquals(0, DramaFilmModes.totalKeptSec(List.of()));
    }

    @Test
    void sameSceneTrimsJoinButNotFirstHeadOrLastTail() {
        List<DramaFilmModes.ClipSpan> spans = plan(List.of(shot(1, 9, "客厅", 5), shot(2, 9, "客厅", 5)), null);

        assertEquals(0D, spans.get(0).headSec(), 0.001);
        assertEquals(DramaFilmModes.MATCH_TRIM_SEC, spans.get(0).tailSec(), 0.001);
        assertEquals(DramaFilmModes.MATCH_TRIM_SEC, spans.get(1).headSec(), 0.001);
        assertEquals(0D, spans.get(1).tailSec(), 0.001);
        assertEquals(4.8D, spans.get(0).keptSec(), 0.001);
    }

    @Test
    void sceneChangeTrimsOnlyTheFreezeFrame() {
        List<DramaFilmModes.ClipSpan> chapter = plan(
                List.of(shot(1, 9, "客厅", 5), shot(2, 10, "客厅", 5)), null);
        assertEquals(DramaFilmModes.SCENE_TRIM_SEC, chapter.get(0).tailSec(), 0.001);
        assertEquals(DramaFilmModes.SCENE_TRIM_SEC, chapter.get(1).headSec(), 0.001);

        List<DramaFilmModes.ClipSpan> place = plan(
                List.of(shot(1, 9, "客厅", 5), shot(2, 9, "门口", 5)), null);
        assertEquals(DramaFilmModes.SCENE_TRIM_SEC, place.get(0).tailSec(), 0.001);

        DramaShot host = shot(1, 9, "客厅", 5);
        host.setCharacterNames(List.of("洛白"));
        DramaShot intruder = shot(2, 9, "客厅", 5);
        intruder.setCharacterNames(List.of("局长"));
        List<DramaFilmModes.ClipSpan> cast = plan(List.of(host, intruder), null);
        assertEquals(DramaFilmModes.SCENE_TRIM_SEC, cast.get(0).tailSec(), 0.001);
    }

    @Test
    void speechWindowPullsTrimBackOutOfDialogue() {
        List<List<DramaFilmModes.CueWindow>> cues = List.of(
                List.of(new DramaFilmModes.CueWindow(0.10, 4.95)),
                List.of(new DramaFilmModes.CueWindow(0.05, 2.00)));
        List<DramaFilmModes.ClipSpan> spans = plan(
                List.of(shot(1, 9, "客厅", 5), shot(2, 9, "客厅", 5)), cues);

        assertEquals(0.05D, spans.get(0).tailSec(), 0.001);
        assertEquals(0.05D, spans.get(1).headSec(), 0.001);
    }

    @Test
    void givesUpWhenTrimWouldLeaveTheShotTooShort() {
        List<DramaFilmModes.ClipSpan> pair = DramaFilmModes.plan(DramaFilmModes.SMOOTH,
                List.of(shot(1, 9, "客厅", 2), shot(2, 9, "客厅", 2)),
                List.of(1.3D, 1.3D),
                List.of());
        assertEquals(0D, pair.get(0).tailSec(), 0.001);
        assertEquals(0D, pair.get(1).headSec(), 0.001);

        List<DramaFilmModes.ClipSpan> middle = DramaFilmModes.plan(DramaFilmModes.SMOOTH,
                List.of(shot(1, 9, "客厅", 5), shot(2, 9, "客厅", 2), shot(3, 9, "客厅", 5)),
                List.of(5D, 1.4D, 5D),
                List.of());
        assertEquals(DramaFilmModes.MATCH_TRIM_SEC, middle.get(0).tailSec(), 0.001);
        assertEquals(0D, middle.get(1).headSec(), 0.001);
        assertEquals(0D, middle.get(1).tailSec(), 0.001);
        assertEquals(DramaFilmModes.MATCH_TRIM_SEC, middle.get(2).headSec(), 0.001);
    }

    @Test
    void directModeKeepsEveryFrame() {
        List<DramaFilmModes.ClipSpan> spans = DramaFilmModes.plan(
                DramaFilmModes.DIRECT,
                List.of(shot(1, 9, "客厅", 5), shot(2, 9, "客厅", 5)),
                List.of(5D, 5D),
                List.of());
        assertEquals(0D, spans.get(0).tailSec(), 0.001);
        assertEquals(0D, spans.get(1).headSec(), 0.001);
        assertEquals(DramaFilmModes.SMOOTH, DramaFilmModes.normalize(null));
        assertEquals(DramaFilmModes.DIRECT, DramaFilmModes.normalize("copy"));
    }

    private static List<DramaFilmModes.ClipSpan> plan(List<DramaShot> shots, List<List<DramaFilmModes.CueWindow>> cues) {
        return DramaFilmModes.plan(DramaFilmModes.SMOOTH, shots, null, cues);
    }

    private static DramaShot shot(long id, long chapterId, String location, int durationSec) {
        DramaShot shot = new DramaShot();
        shot.setId(id);
        shot.setChapterId(chapterId);
        shot.setLocationName(location);
        shot.setDurationSec(durationSec);
        return shot;
    }
}
