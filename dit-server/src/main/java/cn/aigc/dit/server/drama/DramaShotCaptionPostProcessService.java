package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.audio.FfmpegAudioExtractor;
import cn.aigc.dit.generate.caption.AssVideoCaptionBurner;
import cn.aigc.dit.generate.caption.CaptionCue;
import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;
import cn.aigc.dit.generate.vad.SpeechSegment;
import cn.aigc.dit.storage.ObjectStorage;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DramaShotCaptionPostProcessService {

    public static final String PROCESSOR_VERSION = "caption-6";

    private final DramaShotCaptionMapper captionMapper;
    private final DramaShotMapper shotMapper;
    private final VoiceActivityDetector voiceActivityDetector;
    private final ForcedAlignmentTimingProvider forcedAlignmentTimingProvider;
    private final VadTimingProvider vadTimingProvider;
    private final AssVideoCaptionBurner assVideoCaptionBurner;
    private final DramaCaptionService dramaCaptionService;
    private final ObjectStorage storage;
    private final GenerateProperties generateProperties;
    private final ObjectMapper objectMapper;

    public void enqueue(Long shotId, Long genTaskId, Long userId, String sourceVideoKey) {
        if (!enabled() || shotId == null || !StringUtils.hasText(sourceVideoKey)) {
            return;
        }
        DramaShotCaption existing = captionMapper.selectOne(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getShotId, shotId)
                .eq(DramaShotCaption::getSourceVideoKey, sourceVideoKey)
                .eq(DramaShotCaption::getProcessorVersion, PROCESSOR_VERSION)
                .last("limit 1"));
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
        try {
            captionMapper.insert(task);
        } catch (RuntimeException e) {
            // The unique key makes concurrent board polling idempotent.
            log.debug("caption task already exists shot={} source={}", shotId, sourceVideoKey, e);
        }
    }

    public void process(DramaShotCaption task) {
        if (task == null) return;
        Path work = null;
        try {
            DramaShot shot = shotMapper.selectById(task.getShotId());
            if (shot == null || !StringUtils.hasText(task.getSourceVideoKey())) {
                fail(task, "分镜或原始视频不存在");
                return;
            }
            work = Files.createTempDirectory("dit-shot-caption-");
            Path video = work.resolve("input" + suffix(task.getSourceVideoKey()));
            Path wav = work.resolve("audio.wav");
            Files.write(video, storage.getBytes(task.getSourceVideoKey()));
            FfmpegAudioExtractor.extractPcm16Mono(video, wav, ffmpeg().getCaptionTimeoutSeconds());
            List<SpeechSegment> speech = voiceActivityDetector.detect(wav);
            AlignmentTextNormalizer.NormalizedText normalizedText =
                    AlignmentTextNormalizer.normalizeDialogue(shot.getDialogue());
            TimingResult forced = forcedAlignmentTimingProvider.provide(wav, shot.getDialogue(), speech);
            TimingResult timing = forced.usable() ? forced : vadTimingProvider.provide(wav, shot.getDialogue(), speech);
            String fallbackReason = forced.usable()
                    ? (speech.isEmpty() ? "VAD_EMPTY_FULL_AUDIO_ALIGNMENT" : null)
                    : forced.failureReason();
            if (!timing.usable() && !speech.isEmpty()) {
                fallbackReason = fallbackReason == null ? "VAD_NO_CAPTIONS" : fallbackReason + ";VAD_NO_CAPTIONS";
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
            if (captions.isEmpty()) {
                if (speech.isEmpty()) {
                    task.setStatus("NO_SPEECH");
                    task.setErrorMessage(null);
                    captionMapper.updateById(task);
                    refreshFilmCaptions(task);
                } else {
                    fail(task, fallbackReason);
                }
                return;
            }
            List<CaptionCue> burnCues = CaptionAligner.toCaptionCues(captions);
            String captionVideoKey = assVideoCaptionBurner.burn(
                    task.getSourceVideoKey(), burnCues, PROCESSOR_VERSION);
            if (!StringUtils.hasText(captionVideoKey) || captionVideoKey.equals(task.getSourceVideoKey())) {
                fail(task, "ASS 字幕视频生成失败");
                return;
            }
            task.setCaptionVideoKey(captionVideoKey);
            task.setStatus("READY");
            task.setErrorMessage(null);
            captionMapper.updateById(task);
            refreshFilmCaptions(task);
        } catch (Exception e) {
            log.warn("shot caption post-process failed shot={} source={}", task.getShotId(), task.getSourceVideoKey(), e);
            fail(task, e.getMessage() == null ? "字幕后处理失败" : e.getMessage());
        } finally {
            deleteTree(work);
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

    private void fail(DramaShotCaption task, String message) {
        task.setStatus("FAILED");
        task.setErrorMessage(message == null ? "字幕后处理失败" : message);
        captionMapper.updateById(task);
    }

    private void refreshFilmCaptions(DramaShotCaption task) {
        try {
            dramaCaptionService.refreshEpisodeVttForShot(task.getShotId(), task.getSourceVideoKey());
        } catch (Exception e) {
            log.warn("refresh assembled film captions failed shot={}", task.getShotId(), e);
        }
    }

    private boolean enabled() {
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
