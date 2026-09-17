package cn.aigc.dit.generate.agnes;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical {@link GenerateRequest} → Agnes Image / Video 2.5 官方字段。
 * Flash 视频固定 {@code size=720P}，参考视频不支持；图片档位走 {@code 1K/2K/3K/4K}。
 */
public final class AgnesRequestMapper {

    public static final int MIN_SECONDS = 4;
    public static final int MAX_SECONDS = 12;
    public static final int FLASH_MAX_IMAGES = 5;
    public static final int FLASH_MAX_AUDIOS = 3;

    static final Set<String> IMAGE_RATIOS = Set.of(
            "1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9");
    static final Set<String> VIDEO_RATIOS = Set.of(
            "21:9", "16:9", "4:3", "1:1", "3:4", "9:16");
    static final Set<String> IMAGE_SIZES = Set.of("1K", "2K", "3K", "4K");

    private AgnesRequestMapper() {
    }

    public static String modelName(GenerateRequest request) {
        if (request.getModelKey() != null && !request.getModelKey().isBlank()) {
            return request.getModelKey().trim();
        }
        String adapter = request.getAdapterKey() == null ? "" : request.getAdapterKey().trim();
        if (AdapterCatalog.AGNES_VIDEO_2_5.equalsIgnoreCase(adapter)
                || GenerateMode.isVideo(request.getMode())) {
            return AdapterCatalog.AGNES_VIDEO_MODEL;
        }
        return AdapterCatalog.AGNES_IMAGE_MODEL;
    }

    public static boolean flashVideo(GenerateRequest request) {
        return modelName(request).toLowerCase(Locale.ROOT).contains("flash");
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

    public static Map<String, Object> toImageBody(GenerateRequest request, List<String> images) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName(request));
        body.put("prompt", safePrompt(request.getPrompt()));
        body.put("size", imageSize(request.getResolution()));
        body.put("ratio", imageRatio(request.getRatio()));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("response_format", "url");
        if (images != null && !images.isEmpty()) {
            extra.put("image", List.copyOf(images));
        }
        body.put("extra_body", extra);
        return body;
    }

    public static Map<String, Object> toVideoBody(GenerateRequest request,
                                                  String firstFrame,
                                                  String lastFrame,
                                                  List<String> images,
                                                  List<String> audios) {
        String mode = outboundVideoMode(request.getMode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName(request));
        body.put("prompt", referencePrompt(request.getPrompt(), images, audios, mode));
        body.put("seconds", String.valueOf(clampSeconds(request.getDurationSec())));
        body.put("mode", mode);
        body.put("size", videoSize(request));
        body.put("aspect_ratio", videoRatio(request.getRatio()));
        body.put("n", 1);
        if ("keyframe".equals(mode)) {
            if (firstFrame != null && !firstFrame.isBlank()) {
                body.put("first_frame", firstFrame);
            }
            if (lastFrame != null && !lastFrame.isBlank()) {
                body.put("last_frame", lastFrame);
            }
        }
        if ("reference".equals(mode)) {
            if (images != null && !images.isEmpty()) {
                body.put("images", List.copyOf(images));
            }
            if (audios != null && !audios.isEmpty()) {
                body.put("audios", List.copyOf(audios));
            }
        }
        return body;
    }

    static String outboundVideoMode(String mode) {
        String canonical = GenerateMode.canonical(mode);
        if (GenerateMode.I2V.equalsIgnoreCase(canonical)) {
            return "keyframe";
        }
        if (GenerateMode.R2V.equalsIgnoreCase(canonical)) {
            return "reference";
        }
        return "text";
    }

    static String imageSize(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return "1K";
        }
        String normalized = resolution.trim().toUpperCase(Locale.ROOT);
        if (IMAGE_SIZES.contains(normalized)) {
            return normalized;
        }
        if ("720P".equals(normalized) || "480P".equals(normalized) || "1080P".equals(normalized)) {
            return "1K";
        }
        return "1K";
    }

    static String imageRatio(String ratio) {
        String normalized = normalizeRatio(ratio);
        if (normalized == null || "自动".equals(normalized) || "auto".equalsIgnoreCase(normalized)) {
            return "1:1";
        }
        if ("5:4".equals(normalized)) {
            return "4:3";
        }
        if ("4:5".equals(normalized)) {
            return "3:4";
        }
        return IMAGE_RATIOS.contains(normalized) ? normalized : "1:1";
    }

    static String videoRatio(String ratio) {
        String normalized = normalizeRatio(ratio);
        if (normalized == null || "自动".equals(normalized) || "auto".equalsIgnoreCase(normalized)) {
            return "16:9";
        }
        return VIDEO_RATIOS.contains(normalized) ? normalized : "16:9";
    }

    static String videoSize(GenerateRequest request) {
        if (flashVideo(request)) {
            return "720P";
        }
        String resolution = request.getResolution() == null ? "" : request.getResolution().trim().toUpperCase(Locale.ROOT);
        if ("2K".equals(resolution) || "4K".equals(resolution) || "1080P".equals(resolution)) {
            return "2K";
        }
        return "720P";
    }

    static String referencePrompt(String prompt, List<String> images, List<String> audios, String mode) {
        String text = prompt == null ? "" : prompt.trim();
        if ("keyframe".equals(mode) && text.isBlank()) {
            return GenerateMode.DEFAULT_I2V_PROMPT;
        }
        if (!"reference".equals(mode)) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<picture") || lower.contains("<audio")) {
            return text;
        }
        StringBuilder prefix = new StringBuilder();
        int imageCount = images == null ? 0 : images.size();
        int audioCount = audios == null ? 0 : audios.size();
        for (int i = 1; i <= imageCount; i++) {
            if (!prefix.isEmpty()) {
                prefix.append(' ');
            }
            prefix.append("<Picture ").append(i).append('>');
        }
        for (int i = 1; i <= audioCount; i++) {
            if (!prefix.isEmpty()) {
                prefix.append(' ');
            }
            prefix.append("<Audio ").append(i).append('>');
        }
        if (prefix.isEmpty()) {
            return text;
        }
        return text.isBlank() ? prefix.toString() : prefix + " " + text;
    }

    private static String normalizeRatio(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return null;
        }
        return ratio.trim().replace('：', ':');
    }

    private static String safePrompt(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }
}
