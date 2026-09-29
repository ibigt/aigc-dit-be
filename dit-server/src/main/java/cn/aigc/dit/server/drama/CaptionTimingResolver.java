package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.vad.SpeechSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** Resolves caption timing from precise to progressively safer fallbacks. */
@Component
@RequiredArgsConstructor
public class CaptionTimingResolver {

    private final ForcedAlignmentTimingProvider forcedAlignmentTimingProvider;
    private final VadTimingProvider vadTimingProvider;
    private final EstimatedTimingProvider estimatedTimingProvider;

    public Resolution resolve(Path audioFile, String dialogue, List<SpeechSegment> speechSegments) {
        List<SpeechSegment> speech = speechSegments == null ? List.of() : speechSegments;
        TimingResult forced = forcedAlignmentTimingProvider.provide(audioFile, dialogue, speech);
        if (forced.usable()) {
            return new Resolution(forced, speech.isEmpty() ? "VAD_EMPTY_FULL_AUDIO_ALIGNMENT" : null);
        }

        TimingResult vad = vadTimingProvider.provide(audioFile, dialogue, speech);
        if (vad.usable()) {
            return new Resolution(vad, append(forced.failureReason(), "FORCED_ALIGNMENT_FALLBACK_VAD"));
        }

        TimingResult estimated = estimatedTimingProvider.provide(audioFile, dialogue, speech);
        if (estimated.usable()) {
            String reason = append(forced.failureReason(), vad.failureReason());
            return new Resolution(estimated, append(reason, "ESTIMATED_TIMING_FALLBACK"));
        }

        String reason = append(forced.failureReason(), vad.failureReason());
        return new Resolution(estimated, append(reason, estimated.failureReason()));
    }

    private static String append(String current, String next) {
        if (next == null || next.isBlank()) return current;
        if (current == null || current.isBlank()) return next;
        return current + ";" + next;
    }

    public record Resolution(TimingResult timing, String fallbackReason) { }
}
