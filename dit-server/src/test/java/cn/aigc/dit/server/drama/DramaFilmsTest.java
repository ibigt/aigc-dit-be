package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.model.resp.DramaFilmResp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaFilmsTest {

    @Test
    void fingerprintFollowsShotIndexNotInsertOrder() {
        DramaShot second = clip(22L, 2, "users/7/b.mp4");
        DramaShot first = clip(11L, 1, "users/7/a.mp4");
        assertEquals(DramaFilms.fingerprint(List.of(first, second)), DramaFilms.fingerprint(List.of(second, first)));
        assertEquals(List.of(11L, 22L), DramaFilms.clips(List.of(second, first)).stream().map(DramaShot::getId).toList());
    }

    @Test
    void fingerprintChangeMarksAssembledFilmStale() {
        DramaEpisode episode = new DramaEpisode();
        episode.setFilmKey("users/7/drama/31/film.mp4");
        List<DramaShot> shots = List.of(clip(11L, 1, "users/7/a.mp4"), clip(22L, 2, "users/7/b.mp4"));
        episode.setFilmFingerprint(DramaFilms.fingerprint(shots));
        assertFalse(DramaFilms.stale(episode, shots));

        shots.get(1).setVideoKey("users/7/b-new.mp4");
        assertTrue(DramaFilms.stale(episode, shots));
    }

    @Test
    void durationChangeMarksAssembledFilmStale() {
        DramaEpisode episode = new DramaEpisode();
        episode.setFilmKey("users/7/drama/31/film.mp4");
        DramaShot shot = clip(11L, 1, "users/7/a.mp4");
        shot.setDurationSec(5);
        episode.setFilmFingerprint(DramaFilms.fingerprint(List.of(shot)));
        assertFalse(DramaFilms.stale(episode, List.of(shot)));
        shot.setDurationSec(10);
        assertTrue(DramaFilms.stale(episode, List.of(shot)));
    }

    @Test
    void dialogueChangeMarksAssembledFilmStale() {
        DramaEpisode episode = new DramaEpisode();
        episode.setFilmKey("users/7/drama/31/film.mp4");
        DramaShot shot = clip(11L, 1, "users/7/a.mp4");
        shot.setDialogue("洛白：旧台词。");
        episode.setFilmFingerprint(DramaFilms.fingerprint(List.of(shot)));
        shot.setDialogue("洛白：新台词。");
        assertTrue(DramaFilms.stale(episode, List.of(shot)));
    }

    @Test
    void staleUsesIncludedShotIdsOnly() {
        DramaEpisode episode = new DramaEpisode();
        episode.setFilmKey("users/7/drama/31/film.mp4");
        DramaShot first = clip(11L, 1, "users/7/a.mp4");
        DramaShot second = clip(22L, 2, "users/7/b.mp4");
        episode.setFilmShotIds(List.of(11L));
        episode.setFilmFingerprint(DramaFilms.fingerprint(List.of(first)));
        assertFalse(DramaFilms.stale(episode, List.of(first, second)));

        second.setVideoKey("users/7/b-new.mp4");
        assertFalse(DramaFilms.stale(episode, List.of(first, second)));

        first.setVideoKey("users/7/a-new.mp4");
        assertTrue(DramaFilms.stale(episode, List.of(first, second)));
    }

    @Test
    void staleWhenIncludedShotMissing() {
        DramaEpisode episode = new DramaEpisode();
        episode.setFilmKey("users/7/drama/31/film.mp4");
        episode.setFilmShotIds(List.of(11L, 22L));
        episode.setFilmFingerprint("anything");
        assertNull(DramaFilms.resolveIncludedShots(episode, List.of(clip(11L, 1, "users/7/a.mp4"))));
        assertTrue(DramaFilms.stale(episode, List.of(clip(11L, 1, "users/7/a.mp4"))));
    }

    @Test
    void describeReportsMissingShotsAndIncludedIds() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setFilmKey("users/7/drama/31/film.mp4");
        List<DramaShot> shots = List.of(clip(11L, 1, "users/7/a.mp4"), clip(22L, 2, null));
        shots.get(0).setDialogue("洛白：字幕。");
        episode.setFilmShotIds(List.of(11L));
        episode.setFilmFingerprint(DramaFilms.fingerprint(List.of(shots.get(0))));

        DramaFilmResp resp = DramaFilms.describe(episode, shots);
        assertEquals(31L, resp.getEpisodeId());
        assertEquals("/api/media?key=users%2F7%2Fdrama%2F31%2Ffilm.mp4", resp.getFilmUrl());
        assertEquals(List.of(11L), resp.getIncludedShotIds());
        assertFalse(resp.getComplete());
        assertEquals(List.of(2), resp.getMissingShotIndexes());
        assertEquals(2, resp.getShotCount());
        assertEquals(1, resp.getReadyShotCount());
        assertFalse(resp.getStale());
        assertTrue(resp.getHasCaptions());
        assertTrue(resp.getShots().get(0).getHasCaptions());
    }

    private static DramaShot clip(Long id, int index, String videoKey) {
        DramaShot shot = new DramaShot();
        shot.setId(id);
        shot.setShotIndex(index);
        shot.setVideoKey(videoKey);
        return shot;
    }
}
