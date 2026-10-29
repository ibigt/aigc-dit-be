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
import cn.aigc.dit.server.drama.model.req.DramaCaptionCueSaveReq;
import cn.aigc.dit.server.drama.model.resp.DramaCaptionDocumentResp;
import cn.aigc.dit.server.media.MediaUrls;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            String vtt = buildFromStorage(clips, DramaFilms.modeOfFingerprint(episode.getFilmFingerprint()));
            objectStorage.put(key, vtt.getBytes(StandardCharsets.UTF_8), "text/vtt; charset=UTF-8");
            return vtt;
        }
    }

    public void storeEpisodeVtt(String filmKey, List<DramaShot> clips, List<Path> localInputs) {
        if (!StringUtils.hasText(filmKey) || clips == null) {
            return;
        }
        List<Double> durations = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            Path input = localInputs != null && i < localInputs.size() ? localInputs.get(i) : null;
            Double probed = input == null ? null : FfmpegSupport.durationSeconds(input);
            durations.add(DramaFilmModes.durationOf(clips.get(i), probed));
        }
        writeEpisodeVtt(filmKey, clips, DramaFilmModes.plan(
                DramaFilmModes.DIRECT, clips, durations, emptyWindows(clips.size())));
    }

    /**
     * 用与拼接相同的裁切窗写 VTT：偏移按裁后时长累加，cue 起点减去该镜头头裁。
     */
    public void storeJoinedEpisodeVtt(String filmKey, List<DramaShot> clips, List<DramaFilmModes.ClipSpan> spans) {
        if (!StringUtils.hasText(filmKey) || clips == null) {
            return;
        }
        if (spans == null) {
            storeEpisodeVtt(filmKey, clips, (List<Path>) null);
            return;
        }
        writeEpisodeVtt(filmKey, clips, spans);
    }

    private void writeEpisodeVtt(String filmKey, List<DramaShot> clips, List<DramaFilmModes.ClipSpan> spans) {
        String vtt = renderTimeline(clips, spans);
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
        String vtt = buildFromStorage(clips, DramaFilms.modeOfFingerprint(episode.getFilmFingerprint()));
        objectStorage.put(captionKey(episode.getFilmKey()),
                vtt.getBytes(StandardCharsets.UTF_8), "text/vtt; charset=UTF-8");
    }

    /** READY + normalized dialogue matches stored subtitle JSON. */
    public boolean hasReadyCaptions(DramaShot shot) {
        return loadConsistentDocument(shot) != null;
    }

    /** Batch lookup for film describe: shotId → hasReadyCaptions. */
    public Map<Long, Boolean> readyCaptionFlags(List<DramaShot> shots) {
        Map<Long, Boolean> flags = new HashMap<>();
        if (shots == null) return flags;
        for (DramaShot shot : shots) {
            if (shot == null || shot.getId() == null) continue;
            flags.put(shot.getId(), hasReadyCaptions(shot));
        }
        return flags;
    }

    public DramaCaptionDocumentResp getDocument(Long shotId) {
        DramaShot shot = requireShot(shotId);
        DramaShotCaption caption = findCaption(shot);
        DramaCaptionDocumentResp resp = new DramaCaptionDocumentResp();
        resp.setShotId(shotId);
        if (caption == null) {
            resp.setStatus(null);
            return resp;
        }
        resp.setStatus(caption.getStatus());
        resp.setCaptionVideoUrl(MediaUrls.of(caption.getCaptionVideoKey()));
        resp.setSubtitleJsonUrl(MediaUrls.of(caption.getSubtitleJsonKey()));
        SubtitleDocument document = readDocument(caption);
        if (document == null) return resp;
        resp.setSourceType(document.sourceType());
        resp.setProcessorVersion(document.processorVersion());
        resp.setDurationMs(document.durationMs());
        resp.setAlignmentConfidence(document.alignmentConfidence());
        resp.setAlignmentCoverage(document.alignmentCoverage());
        resp.setFallbackReason(document.fallbackReason());
        resp.setModelVersion(document.modelVersion());
        resp.setRawText(document.rawText());
        resp.setNormalizedText(document.normalizedText());
        if (document.captions() != null) {
            for (SubtitleCue cue : document.captions()) {
                if (cue == null) continue;
                DramaCaptionDocumentResp.CueItem item = new DramaCaptionDocumentResp.CueItem();
                item.setStartMs(cue.startMs());
                item.setEndMs(cue.endMs());
                item.setText(cue.text());
                resp.getCues().add(item);
            }
        }
        return resp;
    }

    public DramaCaptionDocumentResp saveManualCues(Long shotId, DramaCaptionCueSaveReq req) {
        DramaShot shot = requireShot(shotId);
        DramaShotCaption caption = findCaption(shot);
        if (caption == null || !StringUtils.hasText(caption.getSubtitleJsonKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "字幕尚未生成，无法编辑");
        }
        if (!"READY".equals(caption.getStatus()) && !"NO_SPEECH".equals(caption.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "字幕处理中，请稍后再编辑");
        }
        List<SubtitleCue> cues = new ArrayList<>();
        for (DramaCaptionCueSaveReq.CueItem item : req.getCues()) {
            if (item == null || item.getStartMs() == null || item.getEndMs() == null) continue;
            String text = item.getText() == null ? "" : item.getText().trim();
            if (!StringUtils.hasText(text) || item.getEndMs() <= item.getStartMs() || item.getStartMs() < 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "字幕时间或文本无效");
            }
            cues.add(new SubtitleCue(item.getStartMs(), item.getEndMs(), text));
        }
        if (cues.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少保留一条字幕");
        }
        SubtitleDocument previous = readDocument(caption);
        AlignmentTextNormalizer.NormalizedText normalized =
                AlignmentTextNormalizer.normalizeDialogue(shot.getDialogue());
        long durationMs = previous != null && previous.durationMs() > 0
                ? previous.durationMs()
                : Math.round(actualDuration(shot) * 1000D);
        SubtitleDocument document = new SubtitleDocument(
                "MANUAL",
                DramaShotCaptionPostProcessService.PROCESSOR_VERSION,
                durationMs,
                previous == null ? List.of() : previous.speechSegments(),
                cues,
                previous == null ? 1D : previous.alignmentConfidence(),
                previous == null ? 1D : previous.alignmentCoverage(),
                previous == null ? null : previous.fallbackReason(),
                "manual-1",
                previous == null ? List.of() : previous.alignmentTokens(),
                normalized.rawText(),
                normalized.normalizedText());
        try {
            objectStorage.put(caption.getSubtitleJsonKey(),
                    objectMapper.writeValueAsBytes(document), "application/json");
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "保存字幕失败");
        }
        // Invalidate burned video so export re-burns from edited cues.
        caption.setCaptionVideoKey(null);
        caption.setStatus("READY");
        caption.setErrorMessage(null);
        shotCaptionMapper.updateById(caption);
        refreshEpisodeVttForShot(shot.getId(), shot.getVideoKey());
        return getDocument(shotId);
    }

    public List<DramaFilmModes.CueWindow> speechWindows(DramaShot shot, double durationSec) {
        List<DramaFilmModes.CueWindow> windows = new ArrayList<>();
        for (CaptionCue cue : cuesForShot(shot, durationSec)) {
            windows.add(new DramaFilmModes.CueWindow(cue.startSec(), cue.endSec()));
        }
        return windows;
    }

    private String buildFromStorage(List<DramaShot> clips, String mode) {
        List<Double> durations = new ArrayList<>();
        List<List<DramaFilmModes.CueWindow>> windows = new ArrayList<>();
        for (DramaShot shot : clips) {
            double duration = actualDuration(shot);
            durations.add(duration);
            windows.add(speechWindows(shot, duration));
        }
        return renderTimeline(clips, DramaFilmModes.plan(mode, clips, durations, windows));
    }

    private String renderTimeline(List<DramaShot> clips, List<DramaFilmModes.ClipSpan> spans) {
        if (spans.size() != clips.size()) {
            throw new IllegalStateException("timeline spans mismatch");
        }
        List<CaptionCue> timeline = new ArrayList<>();
        double offset = 0D;
        for (int i = 0; i < clips.size(); i++) {
            DramaFilmModes.ClipSpan span = spans.get(i);
            List<CaptionCue> shifted = shiftCues(
                    cuesForShot(clips.get(i), span.durationSec()), span.headSec(), span.keptSec());
            appendAtOffset(timeline, shifted, offset);
            offset += span.keptSec();
        }
        return DramaCaptionVtt.renderCues(timeline);
    }

    static List<CaptionCue> shiftCues(List<CaptionCue> cues, double headSec, double keptSec) {
        List<CaptionCue> shifted = new ArrayList<>();
        if (cues == null || keptSec <= 0D) {
            return shifted;
        }
        for (CaptionCue cue : cues) {
            if (cue == null) {
                continue;
            }
            double start = cue.startSec() - headSec;
            double end = cue.endSec() - headSec;
            if (end <= 0D || start >= keptSec) {
                continue;
            }
            start = Math.max(0D, start);
            end = Math.min(keptSec, end);
            if (end > start) {
                shifted.add(new CaptionCue(start, end, cue.text()));
            }
        }
        return shifted;
    }

    private static List<List<DramaFilmModes.CueWindow>> emptyWindows(int count) {
        List<List<DramaFilmModes.CueWindow>> windows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            windows.add(List.of());
        }
        return windows;
    }

    private List<CaptionCue> cuesForShot(DramaShot shot, double durationSec) {
        SubtitleDocument document = loadConsistentDocument(shot);
        if (document == null || document.captions() == null) return List.of();
        return document.captions().stream()
                .filter(cue -> cue != null && cue.endMs() > cue.startMs()
                        && StringUtils.hasText(cue.text())
                        && cue.startMs() >= 0 && cue.startMs() < durationSec * 1000D)
                .map(cue -> new CaptionCue(cue.startMs() / 1000D,
                        Math.min(durationSec, cue.endMs() / 1000D), cue.text()))
                .toList();
    }

    private SubtitleDocument loadConsistentDocument(DramaShot shot) {
        if (shot == null || !StringUtils.hasText(shot.getVideoKey())) return null;
        DramaShotCaption caption = findCaption(shot);
        if (caption == null) return null;
        if ("NO_SPEECH".equals(caption.getStatus())) return null;
        if (!"READY".equals(caption.getStatus()) || !StringUtils.hasText(caption.getSubtitleJsonKey())) {
            return null;
        }
        try {
            SubtitleDocument document = objectMapper.readValue(
                    objectStorage.getBytes(caption.getSubtitleJsonKey()), SubtitleDocument.class);
            if (!AlignmentTextNormalizer.normalizeDialogue(shot.getDialogue()).normalizedText()
                    .equals(document.normalizedText())) {
                log.warn("caption dialogue changed after processing, shot={}", shot.getId());
                return null;
            }
            return document;
        } catch (Exception e) {
            log.warn("read processed caption failed, shot={}", shot.getId(), e);
            return null;
        }
    }

    private DramaShotCaption findCaption(DramaShot shot) {
        if (shot == null || !StringUtils.hasText(shot.getVideoKey())) return null;
        return shotCaptionMapper.selectOne(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getShotId, shot.getId())
                .eq(DramaShotCaption::getSourceVideoKey, shot.getVideoKey())
                .eq(DramaShotCaption::getProcessorVersion, DramaShotCaptionPostProcessService.PROCESSOR_VERSION)
                .last("limit 1"));
    }

    private SubtitleDocument readDocument(DramaShotCaption caption) {
        if (caption == null || !StringUtils.hasText(caption.getSubtitleJsonKey())) return null;
        try {
            return objectMapper.readValue(
                    objectStorage.getBytes(caption.getSubtitleJsonKey()), SubtitleDocument.class);
        } catch (Exception e) {
            log.warn("read subtitle document failed key={}", caption.getSubtitleJsonKey(), e);
            return null;
        }
    }

    private static void appendAtOffset(List<CaptionCue> timeline, List<CaptionCue> cues, double offset) {
        for (CaptionCue cue : cues) {
            timeline.add(new CaptionCue(offset + cue.startSec(), offset + cue.endSec(), cue.text()));
        }
    }

    private double actualDuration(DramaShot shot) {
        if (shot == null || !StringUtils.hasText(shot.getVideoKey())) {
            return DramaFilmModes.durationOf(shot, null);
        }
        Path input = null;
        try {
            input = FfmpegSupport.tempFile("dit-caption-probe-", suffix(shot.getVideoKey()));
            Files.write(input, objectStorage.getBytes(shot.getVideoKey()));
            return DramaFilmModes.durationOf(shot, FfmpegSupport.durationSeconds(input));
        } catch (Exception e) {
            log.warn("probe drama shot duration failed, shot={}", shot.getId(), e);
            return DramaFilmModes.durationOf(shot, null);
        } finally {
            deleteQuietly(input);
        }
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
