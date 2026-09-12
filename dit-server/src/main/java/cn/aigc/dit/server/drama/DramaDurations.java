package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.agnes.AgnesRequestMapper;
import cn.aigc.dit.generate.comfyui.ComfyUiGeometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分镜可选时长：合同只有 5/10/15，再与当前视频适配器的真实区间求交。
 */
public final class DramaDurations {

    public static final List<Integer> OPTIONS = List.of(5, 10, 15);

    private DramaDurations() {
    }

    public static List<Integer> supported(String... keys) {
        int min = rangeMin(keys);
        int max = rangeMax(keys);
        List<Integer> values = new ArrayList<>();
        for (int duration : OPTIONS) {
            if (duration >= min && duration <= max) {
                values.add(duration);
            }
        }
        if (values.isEmpty()) {
            return List.of(DramaContract.DEFAULT_DURATION_SEC);
        }
        return List.copyOf(values);
    }

    public static int clamp(Integer requested, String... keys) {
        List<Integer> options = supported(keys);
        if (requested != null && options.contains(requested)) {
            return requested;
        }
        int want = requested == null ? DramaContract.DEFAULT_DURATION_SEC : requested;
        int best = options.get(0);
        int bestDist = Integer.MAX_VALUE;
        for (int duration : options) {
            int dist = Math.abs(duration - want);
            if (dist < bestDist || (dist == bestDist && duration <= want)) {
                best = duration;
                bestDist = dist;
            }
        }
        return best;
    }

    private static int rangeMin(String... keys) {
        if (isAgnes(keys)) {
            return AgnesRequestMapper.MIN_SECONDS;
        }
        if (isMiniMax(keys)) {
            return ComfyUiGeometry.MIN_DURATION_SEC;
        }
        return 4;
    }

    private static int rangeMax(String... keys) {
        if (isAgnes(keys)) {
            return AgnesRequestMapper.MAX_SECONDS;
        }
        if (isMiniMax(keys)) {
            return ComfyUiGeometry.MAX_DURATION_SEC;
        }
        return 15;
    }

    private static boolean isAgnes(String... keys) {
        return containsAny(keys, AdapterCatalog.AGNES_VIDEO_2_5, "agnes");
    }

    private static boolean isMiniMax(String... keys) {
        return containsAny(keys, AdapterCatalog.MINIMAX_H3, "minimax");
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
                if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
