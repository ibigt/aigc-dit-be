package cn.aigc.dit.generate.comfyui;

import java.util.Locale;

/**
 * MiniMax-H3 画幅与帧数：边长 32 对齐，帧数满足 {@code n % 17 == 5}（5s@24fps → 124）。
 */
public final class ComfyUiGeometry {

    public static final int FPS = 24;
    public static final int MIN_DURATION_SEC = 5;
    public static final int MAX_DURATION_SEC = 15;

    private ComfyUiGeometry() {
    }

    public static int durationSec(Integer durationSec) {
        int seconds = durationSec == null ? MIN_DURATION_SEC : durationSec;
        return Math.min(MAX_DURATION_SEC, Math.max(MIN_DURATION_SEC, seconds));
    }

    public static int frameCount(Integer durationSec) {
        int n = Math.max(5, Math.round(durationSec(durationSec) * FPS));
        while (n % 17 != 5) {
            n++;
        }
        return n;
    }

    public static int[] size(String ratio, String resolution) {
        String aspect = normalizeRatio(ratio);
        int longEdge = longEdge(resolution);
        int width;
        int height;
        if ("9:16".equals(aspect)) {
            height = longEdge;
            width = pairShort(longEdge);
        } else if ("1:1".equals(aspect)) {
            width = align(Math.round(longEdge * 0.75f));
            height = width;
        } else if ("4:3".equals(aspect)) {
            width = longEdge;
            height = align(Math.round(longEdge * 3f / 4f));
        } else if ("3:4".equals(aspect)) {
            height = longEdge;
            width = align(Math.round(longEdge * 3f / 4f));
        } else {
            width = longEdge;
            height = pairShort(longEdge);
        }
        return new int[]{Math.max(32, width), Math.max(32, height)};
    }

    /** MiniMax 模板 16:9 对照：864×480 / 1056×608 / 1344×768。 */
    static int pairShort(int longEdge) {
        if (longEdge >= 1344) {
            return 768;
        }
        if (longEdge >= 1056) {
            return 608;
        }
        return 480;
    }

    static String normalizeRatio(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return "16:9";
        }
        String value = ratio.trim().replace('：', ':');
        if ("自动".equals(value) || "auto".equalsIgnoreCase(value)) {
            return "16:9";
        }
        return value;
    }

    static int longEdge(String resolution) {
        String value = resolution == null ? "" : resolution.trim().toLowerCase(Locale.ROOT);
        if (value.contains("1080") || value.contains("2k") || "high".equals(value)) {
            return 1344;
        }
        if (value.contains("720")) {
            return 1056;
        }
        return 864;
    }

    static int align(int value) {
        int aligned = Math.max(32, value - (value % 32));
        return aligned == 0 ? 32 : aligned;
    }
}
