package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.audio.FfmpegAudioExtractor;
import cn.aigc.dit.generate.caption.AssVideoCaptionBurner;
import cn.aigc.dit.generate.caption.CaptionCue;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.storage.ObjectStorage;
import cn.aigc.dit.generate.vad.SpeechSegment;
import cn.aigc.dit.generate.vad.VoiceActivityDetector;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.entity.DramaShotCaption;
import cn.aigc.dit.server.drama.mapper.DramaShotCaptionMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaShotCaptionPostProcessService {

    public static final String PROCESSOR_VERSION = "caption-7";

    private final DramaShotCaptionMapper captionMapper;
    private final DramaShotMapper shotMapper;
    private final VoiceActivityDetector voiceActivityDetector;
    private final CaptionTimingResolver captionTimingResolver;
    private final AssVideoCaptionBurner assVideoCaptionBurner;
    private final DramaCaptionService dramaCaptionService;
    private final ObjectStorage storage;
    private final GenerateProperties generateProperties;
    private final ObjectMapper objectMapper;

    public void enqueue(Long shotId, Long genTaskId, Long userId, String sourceVideoKey) {
        if (shotId == null || !StringUtils.hasText(sourceVideoKey)) {
            return;
        }
        DramaShotCaption existing = findForSource(shotId, sourceVideoKey);
        if (existing != null) {
            return;
        }
        DramaShotCaption task = new DramaShotCaption();
        task.setShotId(shotId);
        task.setGenTaskId(genTaskId);
        task.setUserId(userId);
        task.setSourceVideoKey(sourceVideoKey);
        task.setStatus("PENDING");
        task.setProcessorVersion(PROCESSOR_VERSION);
        task.setAttemptCount(0);
        try {
            captionMapper.insert(task);
        } catch (RuntimeException e) {
            // The unique key makes concurrent board polling idempotent.
            log.debug("caption task already exists shot={} source={}", shotId, sourceVideoKey, e);
        }
    }

    /**
     * Force a new caption run for the same source video (dialogue edit / manual retry).
     * Resets the existing row to PENDING so the unique key does not block reprocessing.
     */
    public DramaShotCaption requeue(Long shotId, Long genTaskId, Long userId, String sourceVideoKey, boolean force) {
        if (shotId == null || !StringUtils.hasText(sourceVideoKey)) {
            return null;
        }
        DramaShotCaption existing = findForSource(shotId, sourceVideoKey);
        if (existing == null) {
            enqueue(shotId, genTaskId, userId, sourceVideoKey);
            return findForSource(shotId, sourceVideoKey);
        }
        if (!force && ("PENDING".equals(existing.getStatus()) || "RUNNING".equals(existing.getStatus()))) {
            return existing;
        }
        existing.setGenTaskId(genTaskId != null ? genTaskId : existing.getGenTaskId());
        existing.setUserId(userId != null ? userId : existing.getUserId());
        existing.setStatus("PENDING");
        existing.setErrorMessage(null);
        existing.setAttemptCount(0);
        existing.setAudioKey(null);
        existing.setSubtitleJsonKey(null);
        existing.setCaptionVideoKey(null);
        captionMapper.updateById(existing);
        return existing;
    }

    public DramaShotCaption retryOwned(Long shotId) {
        DramaShot shot = requireOwnedShot(shotId);
        if (!StringUtils.hasText(shot.getVideoKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分镜尚无视频，无法生成字幕");
        }
        return requeue(shot.getId(), shot.getGenTaskId(), shot.getUserId(), shot.getVideoKey(), true);
    }

    public void process(DramaShotCaption task) {
        if (task == null) return;
        Path work = null;
        try {
            DramaShot shot = shotMapper.selectById(task.getShotId());
            if (shot == null || !StringUtils.hasText(task.getSourceVideoKey())) {
                fail(task, "分镜或原始视频不存在", false);
                return;
            }
            work = Files.createTempDirectory("dit-shot-caption-");
            Path video = work.resolve("input" + suffix(task.getSourceVideoKey()));
            Path wav = work.resolve("audio.wav");
            Files.write(video, storage.getBytes(task.getSourceVideoKey()));
            FfmpegAudioExtractor.extractPcm16Mono(video, wav, ffmpeg().getCaptionTimeoutSeconds());
            List<SpeechSegment> speech = vadEnabled()
                    ? voiceActivityDetector.detect(wav)
                    : List.of();
            AlignmentTextNormalizer.NormalizedText normalizedText =
                    AlignmentTextNormalizer.normalizeDialogue(shot.getDialogue());
            CaptionTimingResolver.Resolution resolution =
                    captionTimingResolver.resolve(wav, shot.getDialogue(), speech);
            TimingResult timing = resolution.timing();
            String fallbackReason = resolution.fallbackReason();
            if ("ESTIMATED".equals(timing.sourceType()) && timing.usable()) {
                log.warn("caption timing fell back to estimation shot={} source={} reason={}",
                        task.getShotId(), task.getSourceVideoKey(), fallbackReason);
            }
            List<SubtitleCue> captions = timing.captions();
            long durationMs = durationMs(wav, speech);
            SubtitleDocument document = new SubtitleDocument(
                    timing.sourceType(), PROCESSOR_VERSION, durationMs, speech, captions,
                    timing.confidence(), timing.coverage(), fallbackReason, timing.modelVersion(),
                    timing.tokens(), normalizedText.rawText(), normalizedText.normalizedText());
            String subtitleKey = siblingKey(task.getSourceVideoKey(), "-" + PROCESSOR_VERSION + "-subtitle", ".json");
            String audioKey = siblingKey(task.getSourceVideoKey(), "-" + PROCESSOR_VERSION + "-audio", ".wav");
            storage.put(audioKey, Files.readAllBytes(wav), "audio/wav");
            storage.put(subtitleKey, objectMapper.writeValueAsBytes(document), "application/json");
            task.setAudioKey(audioKey);
            task.setSubtitleJsonKey(subtitleKey);
            task.setCaptionVideoKey(null);
            if (captions.isEmpty()) {
                // Empty VAD + empty cues: treat as no speech even when dialogue text exists,
                // so we do not FAIL after refusing ESTIMATED over silence.
                if (speech.isEmpty()) {
                    task.setStatus("NO_SPEECH");
                    task.setErrorMessage(null);
                    captionMapper.updateById(task);
                    refreshFilmCaptions(task);
                } else {
                    fail(task, fallbackReason, true);
                }
                return;
            }
            // Soft-preview path: READY once JSON/VTT cues exist. Burn is on-demand.
            task.setStatus("READY");
            task.setErrorMessage(null);
            task.setAttemptCount(0);
            captionMapper.updateById(task);
            refreshFilmCaptions(task);
        } catch (Exception e) {
            log.warn("shot caption post-process failed shot={} source={}", task.getShotId(), task.getSourceVideoKey(), e);
            fail(task, e.getMessage() == null ? "字幕后处理失败" : e.getMessage(), true);
        } finally {
            deleteTree(work);
        }
    }

    /** On-demand ASS burn for export / 「生成烧录版」. */
    public DramaShotCaption burnOwned(Long shotId) {
        DramaShot shot = requireOwnedShot(shotId);
        DramaShotCaption task = findForSource(shot.getId(), shot.getVideoKey());
        if (task == null || !"READY".equals(task.getStatus()) || !StringUtils.hasText(task.getSubtitleJsonKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "字幕尚未就绪，无法烧录");
        }
        if (StringUtils.hasText(task.getCaptionVideoKey())) {
            return task;
        }
        try {
            SubtitleDocument document = objectMapper.readValue(
                    storage.getBytes(task.getSubtitleJsonKey()), SubtitleDocument.class);
            List<CaptionCue> burnCues = CaptionAligner.toCaptionCues(
                    document.captions() == null ? List.of() : document.captions());
            if (burnCues.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "没有可烧录的字幕条目");
            }
            String captionVideoKey = assVideoCaptionBurner.burn(
                    task.getSourceVideoKey(), burnCues, PROCESSOR_VERSION);
            if (!StringUtils.hasText(captionVideoKey) || captionVideoKey.equals(task.getSourceVideoKey())) {
                throw new BizException(ErrorCode.INTERNAL, "ASS 字幕视频生成失败");
            }
            task.setCaptionVideoKey(captionVideoKey);
            captionMapper.updateById(task);
            return task;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("on-demand caption burn failed shot={}", shotId, e);
            throw new BizException(ErrorCode.INTERNAL, "字幕烧录失败");
        }
    }

    /**
     * 生成记录被删、分镜不再引用这条原片时，删掉对应字幕行并返回需要一并释放的派生文件。
     * 原片本身由调用方放入保留清理列表。
     */
    public List<String> detachForSource(Long shotId, String sourceVideoKey) {
        if (shotId == null || !StringUtils.hasText(sourceVideoKey)) {
            return List.of();
        }
        List<DramaShotCaption> rows = captionMapper.selectList(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getShotId, shotId)
                .eq(DramaShotCaption::getSourceVideoKey, sourceVideoKey));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (DramaShotCaption row : rows) {
            if (row == null) {
                continue;
            }
            addDerivative(keys, sourceVideoKey, row.getAudioKey());
            addDerivative(keys, sourceVideoKey, row.getSubtitleJsonKey());
            addDerivative(keys, sourceVideoKey, row.getCaptionVideoKey());
            if (row.getId() != null) {
                captionMapper.deleteById(row.getId());
            }
        }
        return List.copyOf(keys);
    }

    private static void addDerivative(Set<String> keys, String source, String key) {
        if (StringUtils.hasText(key) && !key.equals(source)) {
            keys.add(key);
        }
    }

    public DramaShotCaption findForSource(Long shotId, String sourceVideoKey) {
        if (shotId == null || !StringUtils.hasText(sourceVideoKey)) return null;
        return captionMapper.selectOne(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getShotId, shotId)
                .eq(DramaShotCaption::getSourceVideoKey, sourceVideoKey)
                .eq(DramaShotCaption::getProcessorVersion, PROCESSOR_VERSION)
                .last("limit 1"));
    }

    private void fail(DramaShotCaption task, String message, boolean allowRetry) {
        int attempt = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        String msg = message == null ? "字幕后处理失败" : message;
        if (allowRetry && attempt < 3) {
            task.setAttemptCount(attempt + 1);
            task.setStatus("PENDING");
            task.setErrorMessage(msg);
            captionMapper.updateById(task);
            return;
        }
        task.setStatus("FAILED");
        task.setErrorMessage(msg);
        captionMapper.updateById(task);
    }

    private void refreshFilmCaptions(DramaShotCaption task) {
        try {
            dramaCaptionService.refreshEpisodeVttForShot(task.getShotId(), task.getSourceVideoKey());
        } catch (Exception e) {
            log.warn("refresh assembled film captions failed shot={}", task.getShotId(), e);
        }
    }

    private DramaShot requireOwnedShot(Long shotId) {
        DramaShot shot = shotMapper.selectById(shotId);
        if (shot == null || !shot.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "分镜不存在");
        }
        return shot;
    }

    private boolean vadEnabled() {
        return generateProperties.getVad() != null && generateProperties.getVad().isEnabled();
    }

    private GenerateProperties.Ffmpeg ffmpeg() {
        return generateProperties.getFfmpeg() == null
                ? new GenerateProperties.Ffmpeg()
                : generateProperties.getFfmpeg();
    }

    private static long durationMs(Path wav, List<SpeechSegment> speech) {
        Double duration = FfmpegSupport.durationSeconds(wav);
        if (duration != null && duration > 0) return Math.round(duration * 1000D);
        return speech == null || speech.isEmpty() ? 0L : speech.get(speech.size() - 1).endMs();
    }

    private static String siblingKey(String source, String marker, String extension) {
        int dot = source.lastIndexOf('.');
        String base = dot > 0 ? source.substring(0, dot) : source;
        return base + marker + extension;
    }

    private static String suffix(String source) {
        int dot = source.lastIndexOf('.');
        return dot > 0 ? source.substring(dot).toLowerCase(Locale.ROOT) : ".mp4";
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

}
