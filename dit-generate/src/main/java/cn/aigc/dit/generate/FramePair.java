package cn.aigc.dit.generate;

import org.springframework.util.StringUtils;

/**
 * Canonical 首尾帧（仅图片）。
 */
public class FramePair {
    private String firstFrameKey;
    private String lastFrameKey;

    public FramePair() {
    }

    public FramePair(String firstFrameKey, String lastFrameKey) {
        this.firstFrameKey = firstFrameKey;
        this.lastFrameKey = lastFrameKey;
    }

    public boolean hasAny() {
        return StringUtils.hasText(firstFrameKey) || StringUtils.hasText(lastFrameKey);
    }

    public String getFirstFrameKey() {
        return firstFrameKey;
    }

    public FramePair setFirstFrameKey(String firstFrameKey) {
        this.firstFrameKey = firstFrameKey;
        return this;
    }

    public String getLastFrameKey() {
        return lastFrameKey;
    }

    public FramePair setLastFrameKey(String lastFrameKey) {
        this.lastFrameKey = lastFrameKey;
        return this;
    }
}
