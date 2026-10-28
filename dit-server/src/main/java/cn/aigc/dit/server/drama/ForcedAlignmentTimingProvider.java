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

/** Uses VAD only to trim the search audio, then aligns the known dialogue. */
@Slf4j
@Component
public class ForcedAlignmentTimingProvider implements CaptionTimingProvider {

    private static final double MIN_CONFIDENCE_WITHOUT_VAD = 0.25D;

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
        List<String> phrases = CaptionAligner.phrases(dialogue);
        if (phrases.isEmpty()) return failure("TRANSCRIPT_EMPTY");
        if (speechSegments == null || speechSegments.isEmpty()) {
            return alignFullAudio(audioFile, phrases, config);
        }
        // One island: crop that span once. Two+: distribute phrases by duration weight per island.
        return alignIslands(audioFile, phrases, speechSegments, config);
    }

    private TimingResult alignFullAudio(Path audioFile, List<String> phrases,
                                        GenerateProperties.ForcedAlignment config) {
        String rawText = String.join("", phrases);
        String normalizedText = AlignmentTextNormalizer.normalizeText(rawText);
        if (normalizedText.isBlank()) return failure("TRANSCRIPT_EMPTY");

        Path cropped = null;
        try {
            Pcm16Audio audio = Pcm16Audio.read(audioFile, config.getSampleRate());
            long cropEndMs = Math.round(audio.samples().length * 1000D / config.getSampleRate());
            int end = (int) Math.min(audio.samples().length,
                    Math.max(1L, Math.round(cropEndMs * config.getSampleRate() / 1000D)));
            float[] crop = java.util.Arrays.copyOfRange(audio.samples(), 0, end);
            cropped = Files.createTempFile("dit-forced-alignment-", ".wav");
            Pcm16WavWriter.write(cropped, crop, config.getSampleRate());

            AlignmentResult result = alignmentService.align(cropped, normalizedText);
            AlignmentConfidenceEvaluator.Decision decision = confidenceEvaluator.evaluate(
                    result, normalizedText.codePointCount(0, normalizedText.length()),
                    Math.max(1L, Math.round(crop.length * 1000D / config.getSampleRate())));
            if (!decision.accepted()) return failure(decision.reason(), result);
            if (result.confidence() < MIN_CONFIDENCE_WITHOUT_VAD) {
                return failure("ALIGNMENT_LOW_CONFIDENCE_WITHOUT_VAD", result);
            }

            List<AlignmentToken> absoluteTokens = result.tokens().stream()
                    .map(token -> new AlignmentToken(token.text(), token.startMs(),
                            token.endMs(), token.confidence(), token.normalizedIndex()))
                    .toList();
            List<SubtitleCue> captions = captions(phrases, absoluteTokens);
            if (captions.isEmpty()) return failure("ALIGNMENT_NO_CAPTIONS", result);
            return new TimingResult("FORCED_ALIGNMENT_FULL_AUDIO",
                    result.confidence(), result.coverage(), captions,
                    absoluteTokens, null, result.modelVersion());
        } catch (Exception e) {
            log.warn("forced-alignment timing provider failed for {}", audioFile, e);
            return failure(e.getMessage() == null ? "ALIGNMENT_EXCEPTION" : e.getMessage());
        } finally {
            if (cropped != null) try { Files.deleteIfExists(cropped); } catch (Exception ignored) { }
        }
    }

    private TimingResult alignIslands(Path audioFile, List<String> phrases,
                                      List<SpeechSegment> speechSegments,
                                      GenerateProperties.ForcedAlignment config) {
        int[] phraseCounts = CaptionAligner.distributeByWeight(
                phrases.size(),
                speechSegments.stream().map(SpeechSegment::durationMs).toList());
        List<Path> crops = new ArrayList<>();
        try {
            Pcm16Audio audio = Pcm16Audio.read(audioFile, config.getSampleRate());
            List<SubtitleCue> allCaptions = new ArrayList<>();
            List<AlignmentToken> allTokens = new ArrayList<>();
            double weightedConfidence = 0D;
            double weightedCoverage = 0D;
            long weightSum = 0L;
            String modelVersion = properties.getForcedAlignment().getProcessorVersion();
            int phraseCursor = 0;

            for (int i = 0; i < speechSegments.size(); i++) {
                int count = phraseCounts[i];
                if (count <= 0) continue;
                int phraseEnd = phraseCursor + count;
                List<String> assigned = phrases.subList(phraseCursor, phraseEnd);
                phraseCursor = phraseEnd;

                SpeechSegment island = speechSegments.get(i);
                String rawText = String.join("", assigned);
                String normalizedText = AlignmentTextNormalizer.normalizeText(rawText);
                if (normalizedText.isBlank()) continue;

                int start = (int) Math.min(audio.samples().length,
                        Math.max(0L, Math.round(island.startMs() * config.getSampleRate() / 1000D)));
                int end = (int) Math.min(audio.samples().length,
                        Math.max(start + 1L, Math.round(island.endMs() * config.getSampleRate() / 1000D)));
                if (end <= start) return failure("INVALID_VAD_CROP");
                float[] crop = java.util.Arrays.copyOfRange(audio.samples(), start, end);
                Path cropped = Files.createTempFile("dit-forced-alignment-", ".wav");
                crops.add(cropped);
                Pcm16WavWriter.write(cropped, crop, config.getSampleRate());

                AlignmentResult result = alignmentService.align(cropped, normalizedText);
                long cropDurationMs = Math.max(1L, Math.round(crop.length * 1000D / config.getSampleRate()));
                AlignmentConfidenceEvaluator.Decision decision = confidenceEvaluator.evaluate(
                        result, normalizedText.codePointCount(0, normalizedText.length()), cropDurationMs);
                if (!decision.accepted()) return failure(decision.reason(), result);

                List<AlignmentToken> absoluteTokens = result.tokens().stream()
                        .map(token -> new AlignmentToken(token.text(),
                                token.startMs() + island.startMs(),
                                token.endMs() + island.startMs(),
                                token.confidence(), token.normalizedIndex()))
                        .toList();
                List<SubtitleCue> islandCaptions = captions(assigned, absoluteTokens);
                if (islandCaptions.isEmpty()) return failure("ALIGNMENT_NO_CAPTIONS", result);

                allCaptions.addAll(islandCaptions);
                allTokens.addAll(absoluteTokens);
                long weight = Math.max(1L, island.durationMs());
                weightedConfidence += result.confidence() * weight;
                weightedCoverage += result.coverage() * weight;
                weightSum += weight;
                modelVersion = result.modelVersion();
            }

            if (allCaptions.isEmpty()) return failure("ALIGNMENT_NO_CAPTIONS");
            double confidence = weightSum == 0L ? 0D : weightedConfidence / weightSum;
            double coverage = weightSum == 0L ? 0D : weightedCoverage / weightSum;
            return new TimingResult("FORCED_ALIGNMENT", confidence, coverage,
                    List.copyOf(allCaptions), List.copyOf(allTokens), null, modelVersion);
        } catch (Exception e) {
            log.warn("forced-alignment timing provider failed for {}", audioFile, e);
            return failure(e.getMessage() == null ? "ALIGNMENT_EXCEPTION" : e.getMessage());
        } finally {
            for (Path cropped : crops) {
                try { Files.deleteIfExists(cropped); } catch (Exception ignored) { }
            }
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
