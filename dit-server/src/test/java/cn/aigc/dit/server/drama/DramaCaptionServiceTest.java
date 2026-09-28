package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.entity.DramaShotCaption;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotCaptionMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaCaptionServiceTest {

    @Mock private DramaEpisodeMapper episodeMapper;
    @Mock private DramaShotMapper shotMapper;
    @Mock private DramaShotCaptionMapper captionMapper;

    @TempDir Path tempDir;

    @Test
    void assembledVttUsesAlignedShotCuesAndOffsetsThemByClipDuration() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir.resolve("obj"));
        ObjectMapper objectMapper = new ObjectMapper();
        DramaCaptionService service = new DramaCaptionService(
                episodeMapper, shotMapper, captionMapper, storage, objectMapper);
        DramaShot first = shot(11L, "users/7/first.mp4", "旁白：第一句。", 5);
        DramaShot second = shot(12L, "users/7/second.mp4", "旁白：第二句。", 4);
        storage.put("users/7/first-subtitle.json", objectMapper.writeValueAsBytes(document(first,
                new SubtitleCue(700, 2200, "第一句。"))), "application/json");
        storage.put("users/7/second-subtitle.json", objectMapper.writeValueAsBytes(document(second,
                new SubtitleCue(500, 1700, "第二句。"))), "application/json");
        when(captionMapper.selectOne(any())).thenReturn(
                caption(first, "users/7/first-subtitle.json", "READY"),
                caption(second, "users/7/second-subtitle.json", "READY"));

        service.storeEpisodeVtt("users/7/film.mp4", List.of(first, second), null);

        String vtt = new String(storage.getBytes(DramaCaptionService.captionKey("users/7/film.mp4")), StandardCharsets.UTF_8);
        assertTrue(vtt.contains("00:00:00.700 --> 00:00:02.200"));
        assertTrue(vtt.contains("00:00:05.500 --> 00:00:06.700"));
        assertFalse(vtt.contains("00:00:00.000 --> 00:00:05.000"));
    }

    @Test
    void noSpeechDoesNotCreateEstimatedCaptions() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir.resolve("obj"));
        DramaCaptionService service = new DramaCaptionService(
                episodeMapper, shotMapper, captionMapper, storage, new ObjectMapper());
        DramaShot shot = shot(11L, "users/7/silent.mp4", "旁白：这句没说出来。", 5);
        when(captionMapper.selectOne(any())).thenReturn(caption(shot, "users/7/silent-subtitle.json", "NO_SPEECH"));

        service.storeEpisodeVtt("users/7/film.mp4", List.of(shot), null);

        assertEquals("WEBVTT\n\n",
                new String(storage.getBytes(DramaCaptionService.captionKey("users/7/film.mp4")), StandardCharsets.UTF_8));
    }

    @Test
    void missingAlignmentDoesNotPublishLengthBasedCaptions() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir.resolve("obj"));
        DramaCaptionService service = new DramaCaptionService(
                episodeMapper, shotMapper, captionMapper, storage, new ObjectMapper());
        DramaShot shot = shot(11L, "users/7/pending.mp4", "旁白：稍后再说。", 5);

        service.storeEpisodeVtt("users/7/film.mp4", List.of(shot), null);

        assertEquals("WEBVTT\n\n",
                new String(storage.getBytes(DramaCaptionService.captionKey("users/7/film.mp4")),
                        StandardCharsets.UTF_8));
    }

    @Test
    void completedShotReplacesPreviouslyStoredFilmVtt() throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir.resolve("obj"));
        ObjectMapper objectMapper = new ObjectMapper();
        DramaCaptionService service = new DramaCaptionService(
                episodeMapper, shotMapper, captionMapper, storage, objectMapper);
        DramaShot shot = shot(11L, "users/7/first.mp4", "旁白：第一句。", 5);
        shot.setEpisodeId(31L);
        DramaEpisode episode = new DramaEpisode();
        episode.setId(31L);
        episode.setFilmKey("users/7/film.mp4");
        episode.setFilmShotIds(List.of(11L));
        storage.put(shot.getVideoKey(), "test-video".getBytes(StandardCharsets.UTF_8), "video/mp4");
        storage.put(DramaCaptionService.captionKey(episode.getFilmKey()),
                "WEBVTT\n\n".getBytes(StandardCharsets.UTF_8), "text/vtt");
        storage.put("users/7/first-subtitle.json", objectMapper.writeValueAsBytes(document(shot,
                new SubtitleCue(700, 2200, "第一句。"))), "application/json");
        when(shotMapper.selectById(11L)).thenReturn(shot);
        when(episodeMapper.selectById(31L)).thenReturn(episode);
        when(shotMapper.selectList(any())).thenReturn(List.of(shot));
        when(captionMapper.selectOne(any())).thenReturn(
                caption(shot, "users/7/first-subtitle.json", "READY"));

        service.refreshEpisodeVttForShot(11L, shot.getVideoKey());

        String vtt = new String(storage.getBytes(DramaCaptionService.captionKey(episode.getFilmKey())),
                StandardCharsets.UTF_8);
        assertTrue(vtt.contains("00:00:00.700 --> 00:00:02.200"));
    }

    private static DramaShot shot(long id, String videoKey, String dialogue, int duration) {
        DramaShot shot = new DramaShot();
        shot.setId(id);
        shot.setVideoKey(videoKey);
        shot.setDialogue(dialogue);
        shot.setDurationSec(duration);
        return shot;
    }

    private static DramaShotCaption caption(DramaShot shot, String subtitleKey, String status) {
        DramaShotCaption caption = new DramaShotCaption();
        caption.setShotId(shot.getId());
        caption.setSourceVideoKey(shot.getVideoKey());
        caption.setSubtitleJsonKey(subtitleKey);
        caption.setStatus(status);
        caption.setProcessorVersion(DramaShotCaptionPostProcessService.PROCESSOR_VERSION);
        return caption;
    }

    private static SubtitleDocument document(DramaShot shot, SubtitleCue cue) {
        AlignmentTextNormalizer.NormalizedText text = AlignmentTextNormalizer.normalizeDialogue(shot.getDialogue());
        return new SubtitleDocument("FORCED_ALIGNMENT", DramaShotCaptionPostProcessService.PROCESSOR_VERSION,
                shot.getDurationSec() * 1000L, List.of(), List.of(cue), 0.9, 1D,
                null, "test-model", List.of(), text.rawText(), text.normalizedText());
    }
}
