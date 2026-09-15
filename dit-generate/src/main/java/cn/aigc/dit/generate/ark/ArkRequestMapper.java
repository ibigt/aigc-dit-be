package cn.aigc.dit.generate.ark;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical {@link GenerateRequest} → 火山方舟 Seedance 2.0 / 2.5 官方字段。
 * 2.0 与 2.0 Fast 共用同一套 {@code contents/generations/tasks} 协议。
 */
public final class ArkRequestMapper {

    public static final int MIN_SECONDS = 4;
    public static final int MAX_SECONDS = 15;
    public static final int MAX_IMAGES = 9;
    public static final int MAX_VIDEOS = 3;
    public static final int MAX_AUDIO_2_0 = 3;
    public static final int MAX_AUDIO_2_5 = 10;
    static final Set<String> RATIOS = Set.of(
            "16:9", "4:3", "1:1", "3:4", "9:16", "21:9", "adaptive");
    static final Set<String> RESOLUTIONS_720 = Set.of("480p", "720p");
    static final Set<String> RESOLUTIONS_1080 = Set.of("480p", "720p", "1080p");

    private ArkRequestMapper() {
    }

    public static String modelName(GenerateRequest request) {
        if (request.getModelKey() != null && !request.getModelKey().isBlank()) {
            return request.getModelKey().trim();
        }
        if (AdapterCatalog.SEEDANCE_2_0.equalsIgnoreCase(safe(request.getAdapterKey()))) {
            return AdapterCatalog.SEEDANCE_2_0_MODEL;
        }
        return AdapterCatalog.SEEDANCE_2_5_MODEL;
    }

    public static int clampSeconds(Integer durationSec) {
        int seconds = durationSec == null ? 5 : durationSec;
        if (seconds < MIN_SECONDS) {
            return MIN_SECONDS;
        }
        if (seconds > MAX_SECONDS) {
            return MAX_SECONDS;
        }
        return seconds;
    }

    public static String clampResolution(String resolution) {
        return clampResolution(null, resolution);
    }

    public static String clampResolution(GenerateRequest request) {
        return clampResolution(request, request == null ? null : request.getResolution());
    }

    public static String clampResolution(GenerateRequest request, String resolution) {
        String normalized = resolution == null ? "" : resolution.trim().toLowerCase(Locale.ROOT);
        Set<String> allowed = allows1080p(request) ? RESOLUTIONS_1080 : RESOLUTIONS_720;
        if (allowed.contains(normalized)) {
            return normalized;
        }
        return "720p";
    }

    static boolean allows1080p(GenerateRequest request) {
        String model = modelName(request == null ? new GenerateRequest() : request).toLowerCase(Locale.ROOT);
        String adapter = request == null ? "" : safe(request.getAdapterKey()).toLowerCase(Locale.ROOT);
        if (model.contains("fast") || model.contains("2-5") || model.contains("2.5")
                || AdapterCatalog.SEEDANCE_2_5.equals(adapter)) {
            return false;
        }
        return AdapterCatalog.SEEDANCE_2_0.equals(adapter) || model.contains("doubao-seedance-2-0");
    }

    /** 本适配器真实可出的清晰度档位（合同大写键），Ark 上限 1080P、无 2K。 */
    public static List<String> supportedResolutions(String adapterKey, String modelKey) {
        GenerateRequest probe = new GenerateRequest();
        probe.setAdapterKey(adapterKey);
        probe.setModelKey(modelKey);
        return allows1080p(probe) ? List.of("480P", "720P", "1080P") : List.of("480P", "720P");
    }

    public static Map<String, Object> toCreateBody(GenerateRequest request,
                                                   String firstFrame,
                                                   String lastFrame,
                                                   List<String> images) {
        return toCreateBody(request, firstFrame, lastFrame, images, List.of());
    }

    public static Map<String, Object> toCreateBody(GenerateRequest request,
                                                   String firstFrame,
                                                   String lastFrame,
                                                   List<String> images,
                                                   List<String> videos) {
        return toCreateBody(request, firstFrame, lastFrame, images, videos, List.of());
    }

    public static Map<String, Object> toCreateBody(GenerateRequest request,
                                                   String firstFrame,
                                                   String lastFrame,
                                                   List<String> images,
                                                   List<String> videos,
                                                   List<String> audios) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName(request));
        body.put("content", content(request.getPrompt(), firstFrame, lastFrame, images, videos, audios));
        body.put("resolution", clampResolution(request));
        body.put("ratio", videoRatio(request.getRatio()));
        body.put("duration", clampSeconds(request.getDurationSec()));
        body.put("generate_audio", generateAudio(request));
        body.put("watermark", false);
        return body;
    }

    static List<Map<String, Object>> content(String prompt,
                                             String firstFrame,
                                             String lastFrame,
                                             List<String> images,
                                             List<String> videos,
                                             List<String> audios) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (prompt != null && !prompt.isBlank()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", prompt.trim());
            items.add(text);
        }
        if (firstFrame != null && !firstFrame.isBlank()) {
            items.add(imageItem(firstFrame, "first_frame"));
        }
        if (lastFrame != null && !lastFrame.isBlank()) {
            items.add(imageItem(lastFrame, "last_frame"));
        }
        appendUrls(items, images, "image_url", "image_url", "reference_image");
        appendUrls(items, videos, "video_url", "video_url", "reference_video");
        appendUrls(items, audios, "audio_url", "audio_url", "reference_audio");
        return items;
    }

    public static int maxImages(GenerateRequest request) {
        return isSeedance25(request) ? 30 : MAX_IMAGES;
    }

    public static int maxVideos(GenerateRequest request) {
        return isSeedance25(request) ? 10 : MAX_VIDEOS;
    }

    public static int maxAudios(GenerateRequest request) {
        return isSeedance25(request) ? MAX_AUDIO_2_5 : MAX_AUDIO_2_0;
    }

    public static boolean supportsAudioOnly(GenerateRequest request) {
        return isSeedance25(request);
    }

    private static boolean isSeedance25(GenerateRequest request) {
        return AdapterCatalog.SEEDANCE_2_5.equals(AdapterCatalog.resolveKeyForGenerate(
                request.getAdapterKey(), null, request.getCategory(), request.getModelKey()));
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

    static String videoRatio(String ratio) {
        String normalized = ratio == null ? "" : ratio.trim().replace('：', ':');
        if (normalized.isBlank() || "自动".equals(normalized) || "auto".equalsIgnoreCase(normalized)) {
            return "adaptive";
        }
        return RATIOS.contains(normalized) ? normalized : "16:9";
    }

    static boolean generateAudio(GenerateRequest request) {
        Object configured = request.getAdapterConfig() == null
                ? null : request.getAdapterConfig().get("generateAudio");
        if (configured instanceof Boolean value) {
            return value;
        }
        if (configured != null) {
            return Boolean.parseBoolean(String.valueOf(configured));
        }
        return true;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
