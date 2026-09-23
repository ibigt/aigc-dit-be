package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.alignment.AlignmentToken;

import java.util.List;

public record TimingResult(
        String sourceType,
        double confidence,
        double coverage,
        List<SubtitleCue> captions,
        List<AlignmentToken> tokens,
        String failureReason,
        String modelVersion) {

    public TimingResult {
        captions = captions == null ? List.of() : List.copyOf(captions);
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    public boolean usable() {
        return !captions.isEmpty();
    }
}
