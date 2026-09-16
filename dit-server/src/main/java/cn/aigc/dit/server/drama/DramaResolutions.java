package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.ark.ArkRequestMapper;

import java.util.List;
import java.util.Locale;

/**
 * 分镜成片清晰度：合同 480P / 720P / 1080P / 2K，再与当前视频适配器的真实档位求交。
 * keys 约定与 {@link DramaDurations} 一致：适配器 key 在前、模型 key 在后。
 */
public final class DramaResolutions {

    public static final List<String> OPTIONS = DramaContract.RESOLUTION_OPTIONS;

    private static final List<String> UP_TO_720P = List.of("480P", "720P");

    private DramaResolutions() {
    }

    public static List<String> supported(String... keys) {
        if (isAgnes(keys)) {
            return List.of("720P");
        }
        if (isArk(keys)) {
            return ArkRequestMapper.supportedResolutions(firstKey(keys), lastKey(keys));
        }
        if (isMiniMaxMax(keys)) {
            return UP_TO_720P;
        }
        return OPTIONS;
    }

    /** 请求档位不被当前适配器支持时，降到不超过请求档位的最高档。 */
    public static String clamp(String requested, String... keys) {
        List<String> options = supported(keys);
        String want = requested == null ? "" : requested.trim().toUpperCase(Locale.ROOT);
        if (options.contains(want)) {
            return want;
        }
        if (want.isEmpty()) {
            return options.contains(DramaContract.DEFAULT_RESOLUTION)
                    ? DramaContract.DEFAULT_RESOLUTION
                    : options.get(options.size() - 1);
        }
        int wantRank = rank(want);
        String best = options.get(0);
        for (String option : options) {
            if (rank(option) <= wantRank) {
                best = option;
            }
        }
        return best;
    }

    private static int rank(String resolution) {
        return switch (resolution) {
            case "480P" -> 0;
            case "1080P" -> 2;
            case "2K" -> 3;
            case "4K" -> 4;
            default -> 1;
        };
    }

    private static String firstKey(String... keys) {
        return keys == null || keys.length == 0 ? null : keys[0];
    }

    private static String lastKey(String... keys) {
        if (keys == null || keys.length == 0) {
            return null;
        }
        return keys[keys.length - 1];
    }

    private static boolean isAgnes(String... keys) {
        return containsAny(keys, AdapterCatalog.AGNES_VIDEO_2_5, "agnes");
    }

    private static boolean isMiniMaxMax(String... keys) {
        return containsAny(keys, "h3-max", "h3max");
    }

    private static boolean isArk(String... keys) {
        String adapter = firstKey(keys);
        if (adapter == null || adapter.isBlank()) {
            return false;
        }
        String value = adapter.trim().toLowerCase(Locale.ROOT);
        return AdapterCatalog.isArk(value)
                || AdapterCatalog.SEEDANCE_2_0.equals(value)
                || AdapterCatalog.SEEDANCE_2_5.equals(value);
    }

    private static boolean containsAny(String[] keys, String... needles) {
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String lower = key.trim().toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (lower.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }
}
