package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.alignment.AlignmentResult;
import cn.aigc.dit.generate.alignment.AlignmentToken;
import org.springframework.stereotype.Component;

/** Rejects technically completed alignments that are not safe for production captions. */
@Component
public class AlignmentConfidenceEvaluator {

    private final GenerateProperties.ForcedAlignment properties;

    public AlignmentConfidenceEvaluator(GenerateProperties properties) {
        this.properties = properties.getForcedAlignment() == null
                ? new GenerateProperties.ForcedAlignment() : properties.getForcedAlignment();
    }

    public Decision evaluate(AlignmentResult result, int normalizedCodePoints, long maxRelativeEndMs) {
        if (result == null || !result.success()) return new Decision(false, "ALIGNMENT_FAILED");
        if (result.coverage() < properties.getMinCoverage()) return new Decision(false, "ALIGNMENT_LOW_COVERAGE");
        if (result.confidence() < properties.getMinConfidence()) return new Decision(false, "ALIGNMENT_LOW_CONFIDENCE");
        if (result.tokens().isEmpty()) return new Decision(false, "ALIGNMENT_NO_TOKENS");
        int previousIndex = -1;
        long previousStart = -1;
        for (AlignmentToken token : result.tokens()) {
            if (token.normalizedIndex() <= previousIndex || token.startMs() < previousStart
                    || token.endMs() <= token.startMs() || token.endMs() > maxRelativeEndMs + 200) {
                return new Decision(false, "ALIGNMENT_TIME_INVALID");
            }
            previousIndex = token.normalizedIndex();
            previousStart = token.startMs();
        }
        if (normalizedCodePoints <= 0 || result.tokens().size() / (double) normalizedCodePoints
                < properties.getMinCoverage()) {
            return new Decision(false, "ALIGNMENT_TOKEN_COVERAGE_LOW");
        }
        return new Decision(true, null);
    }

    public record Decision(boolean accepted, String reason) {
    }
}
