package cn.aigc.dit.generate.dashscope;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

public final class DashScopeRequestMapper {

    public static final int MIN_SECONDS = 5;
    public static final int MAX_SECONDS = 10;
    public static final int MAX_REFERENCE_IMAGES = 10;
    public static final int MAX_REFERENCE_VIDEOS = 5;
    public static final int MAX_REFERENCE_AUDIOS = 5;

    public record MediaItem(String type, String url) {
    }

    private DashScopeRequestMapper() {
    }

    public static String modelName(GenerateRequest request) {
        if (request.getModelKey() != null && !request.getModelKey().isBlank()) {
            return request.getModelKey().trim();
        }
        return AdapterCatalog.WAN3_VIDEO_MODEL;
    }

    public static int clampSeconds(Integer durationSec) {
        int seconds = durationSec == null ? MIN_SECONDS : durationSec;
        return Math.min(MAX_SECONDS, Math.max(MIN_SECONDS, seconds));
    }

    public static Map<String, Object> toCreateBody(GenerateRequest request,
                                                   List<MediaItem> mediaItems) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", prompt(request));
        List<MediaItem> media = mediaItems == null ? List.of() : mediaItems.stream()
                .filter(item -> item != null && hasText(item.type()) && hasText(item.url()))
                .toList();
        if (!media.isEmpty()) {
            input.put("media", media.stream()
                    .map(item -> {
                        Map<String, Object> value = new LinkedHashMap<>();
                        value.put("type", item.type());
                        value.put("url", item.url());
                        return value;
                    })
                    .toList());
        }
        String negativePrompt = configText(request, "negativePrompt");
        if (hasText(negativePrompt)) {
            input.put("negative_prompt", negativePrompt);
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("resolution", resolution(request));
        parameters.put("ratio", ratio(request));
        parameters.put("duration", duration(request));
        if (configValue(request, "promptExtend") != null) {
            parameters.put("prompt_extend", promptExtend(request));
        }
        if (configValue(request, "generateAudio") != null) {
            parameters.put("audio", generateAudio(request));
        }
        Long seed = seed(request);
        if (seed != null) {
            parameters.put("seed", seed);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName(request));
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    static String prompt(GenerateRequest request) {
        if (GenerateMode.I2V.equalsIgnoreCase(GenerateMode.canonical(request.getMode()))) {
            return GenerateMode.i2vPrompt(request.getPrompt());
        }
        return request.getPrompt() == null ? "" : request.getPrompt().trim();
    }

    public static int duration(GenerateRequest request) {
        Object configured = request.getAdapterConfig() == null ? null : request.getAdapterConfig().get("duration");
        if (configured instanceof Number number) {
            return clampSeconds(number.intValue());
        }
        if (configured != null) {
            try {
                return clampSeconds(Integer.parseInt(String.valueOf(configured)));
            } catch (NumberFormatException ignored) {
                return clampSeconds(request.getDurationSec());
            }
        }
        return clampSeconds(request.getDurationSec());
    }

    static boolean promptExtend(GenerateRequest request) {
        Object configured = configValue(request, "promptExtend");
        return configured == null || Boolean.parseBoolean(String.valueOf(configured));
    }

    static boolean generateAudio(GenerateRequest request) {
        Object configured = configValue(request, "generateAudio");
        return configured == null || Boolean.parseBoolean(String.valueOf(configured));
    }

    static Long seed(GenerateRequest request) {
        Object configured = configValue(request, "seed");
        if (configured instanceof Number number) {
            return number.longValue();
        }
        if (configured != null && hasText(String.valueOf(configured))) {
            try {
                return Long.parseLong(String.valueOf(configured));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String ratio(GenerateRequest request) {
        String raw = request.getRatio() == null ? "" : request.getRatio().trim()
                .replace('：', ':').toLowerCase(Locale.ROOT);
        if (raw.isBlank() || "auto".equalsIgnoreCase(raw)) {
            return "adaptive";
        }
        return switch (raw) {
            case "9:16" -> "9:16";
            case "1:1" -> "1:1";
            case "4:3" -> "4:3";
            case "3:4" -> "3:4";
            case "adaptive" -> "adaptive";
            case "16:9" -> "16:9";
            default -> "adaptive";
        };
    }

    public static String resolution(GenerateRequest request) {
        String override = configText(request, "resolution");
        if (hasText(override)) {
            return normalizeResolution(override);
        }
        return normalizeResolution(request.getResolution());
    }

    private static String normalizeResolution(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "480p" -> "480P";
            case "1080p" -> "1080P";
            default -> "720P";
        };
    }

    private static Object configValue(GenerateRequest request, String key) {
        return request.getAdapterConfig() == null ? null : request.getAdapterConfig().get(key);
    }

    private static String configText(GenerateRequest request, String key) {
        Object value = configValue(request, key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
