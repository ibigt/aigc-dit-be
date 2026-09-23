package cn.aigc.dit.generate.vad;

/** A voiced interval in an audio track, expressed in milliseconds. */
public record SpeechSegment(long startMs, long endMs) {

    public SpeechSegment {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("invalid speech segment");
        }
    }

    public long durationMs() {
        return endMs - startMs;
    }
}
