package cn.aigc.dit.generate.alignment;

import java.util.List;

public record AlignmentResult(
        boolean success,
        double confidence,
        double coverage,
        List<AlignmentToken> tokens,
        String failureReason,
        String modelVersion) {

    public AlignmentResult {
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    public static AlignmentResult failure(String reason, String modelVersion) {
        return new AlignmentResult(false, 0D, 0D, List.of(), reason, modelVersion);
    }
}
