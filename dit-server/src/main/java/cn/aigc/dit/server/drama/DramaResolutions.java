package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.AdapterCatalog;

import java.util.List;
import java.util.Locale;

/**
 * 分镜成片清晰度：合同只有 480P / 720P，再与当前视频适配器求交。
 */
public final class DramaResolutions {

    public static final List<String> OPTIONS = List.of("480P", "720P");

    private DramaResolutions() {
    }

    public static List<String> supported(String... keys) {
        if (isAgnes(keys)) {
            return List.of("720P");
        }
        return OPTIONS;
    }

    public static String clamp(String requested, String... keys) {
        List<String> options = supported(keys);
        String want = requested == null ? "" : requested.trim().toUpperCase(Locale.ROOT);
        if (options.contains(want)) {
            return want;
        }
        return options.get(options.size() - 1);
    }

    private static boolean isAgnes(String... keys) {
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String lower = key.trim().toLowerCase(Locale.ROOT);
            if (lower.contains(AdapterCatalog.AGNES_VIDEO_2_5) || lower.contains("agnes")) {
                return true;
            }
        }
        return false;
    }
}
