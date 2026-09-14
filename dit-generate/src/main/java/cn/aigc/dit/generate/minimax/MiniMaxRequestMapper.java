package cn.aigc.dit.generate.minimax;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical {@link GenerateRequest} → MiniMax-H3 / H3-Max {@code /v2/video_generation}。
 */
public final class MiniMaxRequestMapper {

    public static final int MIN_SECONDS = 4;
    public static final int MAX_SECONDS = 15;
    public static final int MAX_MIN_SECONDS = 5;
    public static final int MAX_IMAGES = 9;
    public static final int MAX_VIDEOS = 3;
    public static final int MAX_PROMPT_CHARS = 7000;
    static final Set<String> RATIOS = Set.of(
            "adaptive", "21:9", "16:9", "4:3", "1:1", "3:4", "9:16");

    private MiniMaxRequestMapper() {
    }

    public static String modelName(GenerateRequest request) {
        String key = request == null ? "" : safe(request.getModelKey()).trim();
        if (isMax(key)) {
            return AdapterCatalog.MINIMAX_H3_MAX_MODEL;
        }
        if (key.equalsIgnoreCase(AdapterCatalog.MINIMAX_H3_MODEL)
                || key.equalsIgnoreCase("minimax-h3")
                || key.equalsIgnoreCase("minimax.h3")
                || key.isBlank()) {
            return AdapterCatalog.MINIMAX_H3_MODEL;
        }
        return key;
    }

    public static boolean isMax(GenerateRequest request) {
        return isMax(modelName(request));
    }

    public static boolean isMax(String model) {
        String value = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return value.contains("h3-max") || value.contains("h3_max") || value.contains("h3max");
    }

    public static int clampSeconds(Integer durationSec) {
        return clampSeconds(null, durationSec);
    }

    public static int clampSeconds(GenerateRequest request, Integer durationSec) {
        int min = isMax(request) ? MAX_MIN_SECONDS : MIN_SECONDS;
        int seconds = durationSec == null ? Math.max(5, min) : durationSec;
        if (seconds < min) {
            return min;
        }
        if (seconds > MAX_SECONDS) {
            return MAX_SECONDS;
        }
        return seconds;
    }

    public static String clampResolution(GenerateRequest request) {
        return clampResolution(request, request == null ? null : request.getResolution());
    }

    public static String clampResolution(GenerateRequest request, String resolution) {
        String normalized = resolution == null ? "" : resolution.trim().toUpperCase(Locale.ROOT);
        boolean max = isMax(request);
        if (max) {
            if ("480P".equals(normalized) || "480".equals(normalized)) {
                return "480P";
            }
            return "768P";
        }
        if (normalized.contains("2K") || normalized.contains("1080") || normalized.contains("4K")
                || "HIGH".equals(normalized)) {
            return "2K";
        }
        return "768P";
    }

    public static Map<String, Object> toCreateBody(GenerateRequest request,
                                                   String firstFrame,
                                                   String lastFrame,
                                                   List<String> images,
                                                   List<String> videos) {
        String mode = GenerateMode.canonical(request == null ? null : request.getMode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName(request));
        body.put("content", content(request == null ? null : request.getPrompt(), firstFrame, lastFrame, images, videos));
        body.put("resolution", clampResolution(request));
        body.put("duration", clampSeconds(request, request == null ? null : request.getDurationSec()));
        body.put("ratio", videoRatio(mode, request == null ? null : request.getRatio()));
        body.put("aigc_watermark", false);
        return body;
    }

    static List<Map<String, Object>> content(String prompt,
                                             String firstFrame,
                                             String lastFrame,
                                             List<String> images,
                                             List<String> videos) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", clampPrompt(prompt));
        items.add(text);
        if (firstFrame != null && !firstFrame.isBlank()) {
            items.add(imageItem(firstFrame, "first_frame"));
        }
        if (lastFrame != null && !lastFrame.isBlank()) {
            items.add(imageItem(lastFrame, "last_frame"));
        }
        appendUrls(items, images, "image_url", "image_url", "reference_image");
        appendUrls(items, videos, "video_url", "video_url", "reference_video");
        return items;
    }

    static String clampPrompt(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        if (text.length() <= MAX_PROMPT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_PROMPT_CHARS);
    }

    static void appendUrls(List<Map<String, Object>> items, List<String> urls,
                           String type, String urlField, String role) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            if (url != null && !url.isBlank()) {
                items.add(mediaItem(url, type, urlField, role));
            }
        }
    }

    static Map<String, Object> imageItem(String url, String role) {
        return mediaItem(url, "image_url", "image_url", role);
    }

    static Map<String, Object> mediaItem(String url, String type, String urlField, String role) {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("url", url);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put(urlField, nested);
        item.put("role", role);
        return item;
    }

    static String videoRatio(String mode, String ratio) {
        String normalized = ratio == null ? "" : ratio.trim().replace('：', ':');
        boolean auto = normalized.isBlank()
                || "自动".equals(normalized)
                || "auto".equalsIgnoreCase(normalized)
                || "adaptive".equalsIgnoreCase(normalized);
        if (GenerateMode.T2V.equalsIgnoreCase(mode)) {
            if (auto || !RATIOS.contains(normalized) || "adaptive".equalsIgnoreCase(normalized)) {
                return "16:9";
            }
            return normalized;
        }
        if (GenerateMode.I2V.equalsIgnoreCase(mode)) {
            return "adaptive";
        }
        if (auto) {
            return "adaptive";
        }
        return RATIOS.contains(normalized) ? normalized : "16:9";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
