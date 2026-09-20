package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.model.resp.DramaFilmResp;
import cn.aigc.dit.storage.LocalObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaFilmServiceTest {

    @Mock
    private DramaEpisodeMapper episodeMapper;
    @Mock
    private DramaShotMapper shotMapper;
    @Mock
    private PosterExtractor posterExtractor;
    @Mock
    private DramaFilmConcat filmConcat;
    @Mock
    private DramaCaptionService captionService;

    private LocalObjectStorage storage;
    private DramaFilmService service;
    private final List<List<String>> concatPayloads = new ArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(tempDir.resolve("obj"));
        service = new DramaFilmService(episodeMapper, shotMapper, storage, posterExtractor, filmConcat, captionService);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        concatPayloads.clear();
        org.mockito.Mockito.lenient().when(posterExtractor.extract(any())).thenReturn("users/7/drama/31/film-poster.webp");
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            List<Path> inputs = invocation.getArgument(0);
            Path output = invocation.getArgument(1);
            List<String> payloads = new ArrayList<>();
            for (Path input : inputs) {
                payloads.add(Files.readString(input));
            }
            concatPayloads.add(payloads);
            Files.write(output, "assembled".getBytes());
            return null;
        }).when(filmConcat).concat(any(), any());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void getMarksStaleWhenFingerprintNoLongerMatches() {
        DramaEpisode episode = ownedEpisode();
        List<DramaShot> shots = List.of(clip(11L, 1, "users/7/a.mp4"), clip(22L, 2, "users/7/b.mp4"));
        episode.setFilmKey("users/7/drama/31/film.mp4");
        episode.setFilmFingerprint(DramaFilms.fingerprint(shots));
        episode.setFilmShotIds(List.of(11L, 22L));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(shotMapper.selectList(any())).thenReturn(shots);

        DramaFilmResp fresh = service.get(31L);
        assertFalse(fresh.getStale());

        shots.get(0).setVideoKey("users/7/a-new.mp4");
        DramaFilmResp stale = service.get(31L);
        assertTrue(stale.getStale());
        assertEquals("/api/media?key=users%2F7%2Fdrama%2F31%2Ffilm.mp4", stale.getFilmUrl());
    }

    @Test
    void getMarksStaleWhenIncludedShotDeleted() {
        DramaEpisode episode = ownedEpisode();
        episode.setFilmKey("users/7/drama/31/film.mp4");
        episode.setFilmFingerprint("abc");
        episode.setFilmShotIds(List.of(11L, 22L));
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(shotMapper.selectList(any())).thenReturn(List.of(clip(11L, 1, "users/7/a.mp4")));

        assertTrue(service.get(31L).getStale());
    }

    @Test
    void discardAssembledFilmDeletesObjectsAndClearsEpisode() throws Exception {
        storage.put("users/7/drama/31/film.mp4", "assembled".getBytes(), "video/mp4");
        storage.put("users/7/drama/31/film-poster.webp", "poster".getBytes(), "image/webp");
        DramaEpisode episode = ownedEpisode();
        episode.setFilmKey("users/7/drama/31/film.mp4");
        episode.setFilmPosterKey("users/7/drama/31/film-poster.webp");
        episode.setFilmFingerprint("abc");
        episode.setFilmShotIds(List.of(11L, 22L));
        episode.setFilmAssembledAt(java.time.OffsetDateTime.parse("2026-09-14T04:00:00Z"));
        when(episodeMapper.selectById(31L)).thenReturn(episode);

        service.discardAssembledFilm(31L);

        assertThrows(BizException.class, () -> storage.getBytes("users/7/drama/31/film.mp4"));
        assertThrows(BizException.class, () -> storage.getBytes("users/7/drama/31/film-poster.webp"));
        assertNull(episode.getFilmKey());
        assertNull(episode.getFilmFingerprint());
        assertNull(episode.getFilmShotIds());
        verify(episodeMapper).updateById(episode);
    }

    @Test
    void discardAssembledFilmNoopsWhenNoFilm() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        service.discardAssembledFilm(31L);
        verify(episodeMapper, never()).updateById(any(DramaEpisode.class));
    }

    @Test
    void assembleRejectsWhenNoShotHasVideo() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(shotMapper.selectList(any())).thenReturn(List.of(clip(11L, 1, null), clip(22L, 2, "")));

        BizException error = assertThrows(BizException.class, () -> service.assemble(31L));
        assertTrue(error.getMessage().contains("还没有已出片镜头"));
    }

    @Test
    void assembleConcatenatesInShotIndexOrder() throws Exception {
        storage.put("users/7/b.mp4", "second".getBytes(), "video/mp4");
        storage.put("users/7/a.mp4", "first".getBytes(), "video/mp4");
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(shotMapper.selectList(any())).thenReturn(List.of(
                clip(22L, 2, "users/7/b.mp4"),
                clip(11L, 1, "users/7/a.mp4"),
                clip(33L, 3, null)));

        DramaFilmResp resp = service.assemble(31L);

        assertEquals(1, concatPayloads.size());
        assertEquals(List.of("first", "second"), concatPayloads.get(0));
        assertEquals("users/7/drama/31/film.mp4", episode.getFilmKey());
        assertEquals(List.of(11L, 22L), episode.getFilmShotIds());
        assertEquals(DramaFilms.fingerprint(List.of(
                clip(11L, 1, "users/7/a.mp4"),
                clip(22L, 2, "users/7/b.mp4"))), episode.getFilmFingerprint());
        assertEquals(List.of(11L, 22L), resp.getIncludedShotIds());
        assertFalse(resp.getComplete());
        assertEquals(List.of(3), resp.getMissingShotIndexes());
        assertEquals("/api/media?key=users%2F7%2Fdrama%2F31%2Ffilm.mp4", resp.getFilmUrl());
        verify(episodeMapper).updateById(episode);
        ArgumentCaptor<DramaEpisode> captor = ArgumentCaptor.forClass(DramaEpisode.class);
        verify(episodeMapper).updateById(captor.capture());
        assertEquals("users/7/drama/31/film-poster.webp", captor.getValue().getFilmPosterKey());
    }

    @Test
    void assembleFiltersShotIdsAndOrdersByShotIndex() throws Exception {
        storage.put("users/7/b.mp4", "second".getBytes(), "video/mp4");
        storage.put("users/7/a.mp4", "first".getBytes(), "video/mp4");
        storage.put("users/7/c.mp4", "third".getBytes(), "video/mp4");
        DramaEpisode episode = ownedEpisode();
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(shotMapper.selectList(any())).thenReturn(List.of(
                clip(11L, 1, "users/7/a.mp4"),
                clip(22L, 2, "users/7/b.mp4"),
                clip(33L, 3, "users/7/c.mp4")));

        DramaFilmResp resp = service.assemble(31L, List.of(33L, 11L));

        assertEquals(List.of("first", "third"), concatPayloads.get(0));
        assertEquals(List.of(11L, 33L), episode.getFilmShotIds());
        assertEquals(DramaFilms.fingerprint(List.of(
                clip(11L, 1, "users/7/a.mp4"),
                clip(33L, 3, "users/7/c.mp4"))), episode.getFilmFingerprint());
        assertEquals(List.of(11L, 33L), resp.getIncludedShotIds());
        assertFalse(resp.getStale());
    }

    @Test
    void assembleRejectsForeignOrUnreadyShotIds() {
        when(episodeMapper.selectById(31L)).thenReturn(ownedEpisode());
        when(shotMapper.selectList(any())).thenReturn(List.of(
                clip(11L, 1, "users/7/a.mp4"),
                clip(22L, 2, null)));

        BizException foreign = assertThrows(BizException.class, () -> service.assemble(31L, List.of(11L, 99L)));
        assertTrue(foreign.getMessage().contains("不属于本集"));

        BizException unready = assertThrows(BizException.class, () -> service.assemble(31L, List.of(22L)));
        assertTrue(unready.getMessage().contains("尚未出片"));
    }

    private static DramaEpisode ownedEpisode() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setUserId(7L);
        episode.setSeriesId(21L);
        episode.setName("第 1 集");
        return episode;
    }

    private static DramaShot clip(Long id, int index, String videoKey) {
        DramaShot shot = new DramaShot();
        shot.setId(id);
        shot.setEpisodeId(31L);
        shot.setShotIndex(index);
        shot.setVideoKey(videoKey);
        return shot;
    }
}
