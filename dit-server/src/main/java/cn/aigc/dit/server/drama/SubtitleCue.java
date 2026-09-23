package cn.aigc.dit.server.drama;

public record SubtitleCue(long startMs, long endMs, String text) {

    public SubtitleCue {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("invalid subtitle cue");
        }
    }
}
