package cn.aigc.dit.generate.caption;

/**
 * 一段烧进画面的字幕：时间单位秒。
 */
public record CaptionCue(double startSec, double endSec, String text) {

    public boolean hasText() {
        return text != null && !text.isBlank() && endSec > startSec;
    }
}
