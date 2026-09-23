package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.vad.SpeechSegment;
import cn.aigc.dit.generate.alignment.AlignmentToken;

import java.util.List;

public record SubtitleDocument(
        String sourceType,
        String processorVersion,
        long durationMs,
        List<SpeechSegment> speechSegments,
        List<SubtitleCue> captions,
        double alignmentConfidence,
        double alignmentCoverage,
        String fallbackReason,
        String modelVersion,
        List<AlignmentToken> alignmentTokens,
        String rawText,
        String normalizedText) {
}
