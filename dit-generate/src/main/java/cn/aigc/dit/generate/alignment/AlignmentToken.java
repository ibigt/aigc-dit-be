package cn.aigc.dit.generate.alignment;

/** A normalized transcript token mapped to an absolute audio interval. */
public record AlignmentToken(
        String text,
        long startMs,
        long endMs,
        double confidence,
        int normalizedIndex) {

    public AlignmentToken {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("invalid alignment token interval");
        }
    }
}
