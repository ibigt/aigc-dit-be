package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.alignment.AlignmentResult;
import cn.aigc.dit.generate.alignment.AlignmentToken;
import cn.aigc.dit.generate.alignment.ForcedAlignmentService;
import cn.aigc.dit.generate.audio.Pcm16Audio;
import cn.aigc.dit.generate.audio.Pcm16WavWriter;
import cn.aigc.dit.generate.vad.SpeechSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Uses VAD only to trim the search audio, then aligns the known dialogue. */
@Slf4j
@Component
public class ForcedAlignmentTimingProvider implements CaptionTimingProvider {

    private final ForcedAlignmentService alignmentService;
    private final AlignmentConfidenceEvaluator confidenceEvaluator;
    private final GenerateProperties properties;

    public ForcedAlignmentTimingProvider(ForcedAlignmentService alignmentService,
                                         AlignmentConfidenceEvaluator confidenceEvaluator,
                                         GenerateProperties properties) {
        this.alignmentService = alignmentService;
        this.confidenceEvaluator = confidenceEvaluator;
        this.properties = properties;
    }

    @Override
    public TimingResult provide(Path audioFile, String dialogue, List<SpeechSegment> speechSegments) {
        GenerateProperties.ForcedAlignment config = properties.getForcedAlignment();
        if (config == null || !config.isEnabled()) return failure("FORCED_ALIGNMENT_DISABLED");
        if (speechSegments == null || speechSegments.isEmpty()) return failure("NO_SPEECH_SEGMENTS");
        List<String> phrases = CaptionAligner.phrases(dialogue);
        if (phrases.isEmpty()) return failure("TRANSCRIPT_EMPTY");
        String rawText = String.join("", phrases);
        String normalizedText = AlignmentTextNormalizer.normalizeText(rawText);
        if (normalizedText.isBlank()) return failure("TRANSCRIPT_EMPTY");

        Path cropped = null;
        long cropStartMs = speechSegments.get(0).startMs();
        long cropEndMs = speechSegments.get(speechSegments.size() - 1).endMs();
        try {
            Pcm16Audio audio = Pcm16Audio.read(audioFile, config.getSampleRate());
            int start = (int) Math.min(audio.samples().length,
                    Math.max(0L, Math.round(cropStartMs * config.getSampleRate() / 1000D)));
            int end = (int) Math.min(audio.samples().length,
                    Math.max(start + 1L, Math.round(cropEndMs * config.getSampleRate() / 1000D)));
            if (end <= start) return failure("INVALID_VAD_CROP");
            float[] crop = java.util.Arrays.copyOfRange(audio.samples(), start, end);
            cropped = Files.createTempFile("dit-forced-alignment-", ".wav");
            Pcm16WavWriter.write(cropped, crop, config.getSampleRate());

            AlignmentResult result = alignmentService.align(cropped, normalizedText);
            AlignmentConfidenceEvaluator.Decision decision = confidenceEvaluator.evaluate(
                    result, normalizedText.codePointCount(0, normalizedText.length()),
                    Math.max(1L, Math.round(crop.length * 1000D / config.getSampleRate())));
            if (!decision.accepted()) return failure(decision.reason(), result);

            List<AlignmentToken> absoluteTokens = result.tokens().stream()
                    .map(token -> new AlignmentToken(token.text(), token.startMs() + cropStartMs,
                            token.endMs() + cropStartMs, token.confidence(), token.normalizedIndex()))
                    .toList();
            List<SubtitleCue> captions = captions(phrases, absoluteTokens);
            if (captions.isEmpty()) return failure("ALIGNMENT_NO_CAPTIONS", result);
            return new TimingResult("FORCED_ALIGNMENT", result.confidence(), result.coverage(), captions,
                    absoluteTokens, null, result.modelVersion());
        } catch (Exception e) {
            log.warn("forced-alignment timing provider failed for {}", audioFile, e);
            return failure(e.getMessage() == null ? "ALIGNMENT_EXCEPTION" : e.getMessage());
        } finally {
            if (cropped != null) try { Files.deleteIfExists(cropped); } catch (Exception ignored) { }
        }
    }

    private static List<SubtitleCue> captions(List<String> phrases, List<AlignmentToken> tokens) {
        List<SubtitleCue> result = new ArrayList<>();
        int normalizedCursor = 0;
        for (String phrase : phrases) {
            String normalized = AlignmentTextNormalizer.normalizeText(phrase);
            int length = normalized.codePointCount(0, normalized.length());
            if (length == 0) continue;
            int rangeStart = normalizedCursor;
            int rangeEnd = normalizedCursor + length;
            List<AlignmentToken> phraseTokens = tokens.stream()
                    .filter(token -> token.normalizedIndex() >= rangeStart && token.normalizedIndex() < rangeEnd)
                    .toList();
            normalizedCursor = rangeEnd;
            if (phraseTokens.isEmpty()) continue;
            long start = phraseTokens.get(0).startMs();
            long end = phraseTokens.get(phraseTokens.size() - 1).endMs();
            if (end > start) result.add(new SubtitleCue(start, end, phrase));
        }
        return List.copyOf(result);
    }

    private TimingResult failure(String reason) {
        return failure(reason, null);
    }

    private TimingResult failure(String reason, AlignmentResult result) {
        return new TimingResult("FORCED_ALIGNMENT", result == null ? 0D : result.confidence(),
                result == null ? 0D : result.coverage(), List.of(),
                result == null ? List.of() : result.tokens(), reason,
                result == null ? properties.getForcedAlignment().getProcessorVersion() : result.modelVersion());
    }
}
