package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.generate.caption.CaptionCue;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.entity.DramaShotCaption;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotCaptionMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaCaptionService {

    private final DramaEpisodeMapper episodeMapper;
    private final DramaShotMapper shotMapper;
    private final DramaShotCaptionMapper shotCaptionMapper;
    private final ObjectStorage objectStorage;
    private final ObjectMapper objectMapper;

    public String shotVtt(Long shotId) {
        DramaShot shot = requireShot(shotId);
        double duration = actualDuration(shot);
        return DramaCaptionVtt.renderCues(cuesForShot(shot, duration));
    }

    public String episodeVtt(Long episodeId) {
        DramaEpisode episode = requireEpisode(episodeId);
        if (!StringUtils.hasText(episode.getFilmKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先拼接成片");
        }
        String key = captionKey(episode.getFilmKey());
        try {
            return new String(objectStorage.getBytes(key), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            List<DramaShot> clips = DramaFilms.resolveIncludedShots(episode, listShots(episodeId));
            if (clips == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "成片包含的分镜已不存在，请重新拼接成片");
            }
            String vtt = buildFromStorage(clips);
            objectStorage.put(key, vtt.getBytes(StandardCharsets.UTF_8), "text/vtt; charset=UTF-8");
            return vtt;
        }
    }

    public void storeEpisodeVtt(String filmKey, List<DramaShot> clips, List<Path> localInputs) {
        if (!StringUtils.hasText(filmKey)) {
            return;
        }
        List<CaptionCue> timeline = new ArrayList<>();
        double offset = 0D;
        for (int i = 0; i < clips.size(); i++) {
            DramaShot shot = clips.get(i);
            Path input = localInputs != null && i < localInputs.size() ? localInputs.get(i) : null;
            double duration = duration(input, fallbackDuration(shot));
            appendAtOffset(timeline, cuesForShot(shot, duration), offset);
            offset += duration;
        }
        String vtt = DramaCaptionVtt.renderCues(timeline);
        objectStorage.put(captionKey(filmKey), vtt.getBytes(StandardCharsets.UTF_8), "text/vtt; charset=UTF-8");
    }

    public void deleteEpisodeVtt(String filmKey) {
        if (!StringUtils.hasText(filmKey)) {
            return;
        }
        deleteCaptionQuietly(captionKey(filmKey));
        deleteCaptionQuietly(legacyCaptionKey(filmKey));
    }

    private void deleteCaptionQuietly(String key) {
        try {
            objectStorage.delete(key);
        } catch (Exception e) {
            log.debug("delete drama captions failed, key={}", key, e);
        }
    }

    static String captionKey(String filmKey) {
        return captionBase(filmKey) + "-captions-"
                + DramaShotCaptionPostProcessService.PROCESSOR_VERSION + ".vtt";
    }

    private static String legacyCaptionKey(String filmKey) {
        return captionBase(filmKey) + "-captions.vtt";
    }

    private static String captionBase(String filmKey) {
        int dot = filmKey == null ? -1 : filmKey.lastIndexOf('.');
        return dot > 0 ? filmKey.substring(0, dot) : filmKey;
    }

    public void refreshEpisodeVttForShot(Long shotId, String sourceVideoKey) {
        DramaShot shot = shotMapper.selectById(shotId);
        if (shot == null || !StringUtils.hasText(sourceVideoKey)
                || !sourceVideoKey.equals(shot.getVideoKey())) return;
        DramaEpisode episode = episodeMapper.selectById(shot.getEpisodeId());
        if (episode == null || !StringUtils.hasText(episode.getFilmKey())) return;
        List<DramaShot> clips = DramaFilms.resolveIncludedShots(episode, listShots(episode.getId()));
        if (clips == null || clips.stream().noneMatch(clip -> shotId.equals(clip.getId()))) return;
        String vtt = buildFromStorage(clips);
        objectStorage.put(captionKey(episode.getFilmKey()),
                vtt.getBytes(StandardCharsets.UTF_8), "text/vtt; charset=UTF-8");
    }

    private String buildFromStorage(List<DramaShot> clips) {
        List<CaptionCue> timeline = new ArrayList<>();
        double offset = 0D;
        for (DramaShot shot : clips) {
            double duration = actualDuration(shot);
            appendAtOffset(timeline, cuesForShot(shot, duration), offset);
            offset += duration;
        }
        return DramaCaptionVtt.renderCues(timeline);
    }

    private List<CaptionCue> cuesForShot(DramaShot shot, double durationSec) {
        if (shot == null || !StringUtils.hasText(shot.getVideoKey())) return List.of();
        DramaShotCaption caption = shotCaptionMapper.selectOne(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getShotId, shot.getId())
                .eq(DramaShotCaption::getSourceVideoKey, shot.getVideoKey())
                .eq(DramaShotCaption::getProcessorVersion, DramaShotCaptionPostProcessService.PROCESSOR_VERSION)
                .last("limit 1"));
        if (caption == null) {
            // The async caption task will refresh the film track when it finishes.
            return List.of();
        }
        if ("NO_SPEECH".equals(caption.getStatus())) return List.of();
        if (!"READY".equals(caption.getStatus()) || !StringUtils.hasText(caption.getSubtitleJsonKey())) {
            return List.of();
        }
        try {
            SubtitleDocument document = objectMapper.readValue(
                    objectStorage.getBytes(caption.getSubtitleJsonKey()), SubtitleDocument.class);
            if (!AlignmentTextNormalizer.normalizeDialogue(shot.getDialogue()).normalizedText()
                    .equals(document.normalizedText())) {
                log.warn("caption dialogue changed after processing, shot={}", shot.getId());
                return List.of();
            }
            if (document.captions() == null) return List.of();
            return document.captions().stream()
                    .filter(cue -> cue != null && cue.endMs() > cue.startMs()
                            && StringUtils.hasText(cue.text())
                            && cue.startMs() >= 0 && cue.startMs() < durationSec * 1000D)
                    .map(cue -> new CaptionCue(cue.startMs() / 1000D,
                            Math.min(durationSec, cue.endMs() / 1000D), cue.text()))
                    .toList();
        } catch (Exception e) {
            log.warn("read processed caption failed, shot={}", shot.getId(), e);
            return List.of();
        }
    }

    private static void appendAtOffset(List<CaptionCue> timeline, List<CaptionCue> cues, double offset) {
        for (CaptionCue cue : cues) {
            timeline.add(new CaptionCue(offset + cue.startSec(), offset + cue.endSec(), cue.text()));
        }
    }

    private double actualDuration(DramaShot shot) {
        if (shot == null || !StringUtils.hasText(shot.getVideoKey())) {
            return fallbackDuration(shot);
        }
        Path input = null;
        try {
            input = FfmpegSupport.tempFile("dit-caption-probe-", suffix(shot.getVideoKey()));
            Files.write(input, objectStorage.getBytes(shot.getVideoKey()));
            return duration(input, fallbackDuration(shot));
        } catch (Exception e) {
            log.warn("probe drama shot duration failed, shot={}", shot.getId(), e);
            return fallbackDuration(shot);
        } finally {
            deleteQuietly(input);
        }
    }

    private static double duration(Path input, double fallback) {
        Double actual = input == null ? null : FfmpegSupport.durationSeconds(input);
        return actual != null && actual >= 1D ? actual : fallback;
    }

    private static double fallbackDuration(DramaShot shot) {
        return shot == null || shot.getDurationSec() == null || shot.getDurationSec() < 1
                ? DramaContract.DEFAULT_DURATION_SEC
                : shot.getDurationSec();
    }

    private List<DramaShot> listShots(Long episodeId) {
        return shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .eq(DramaShot::getEpisodeId, episodeId)
                .orderByAsc(DramaShot::getShotIndex));
    }

    private DramaShot requireShot(Long id) {
        DramaShot shot = shotMapper.selectById(id);
        if (shot == null || !shot.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "分镜不存在");
        }
        return shot;
    }

    private DramaEpisode requireEpisode(Long id) {
        DramaEpisode episode = episodeMapper.selectById(id);
        if (episode == null || !episode.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        return episode;
    }

    private static String suffix(String storageKey) {
        int dot = storageKey == null ? -1 : storageKey.lastIndexOf('.');
        return dot > 0 ? storageKey.substring(dot) : ".mp4";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 临时探测文件清理失败不影响字幕返回。
        }
    }
}
