package cn.aigc.dit.generate.runninghub;

import cn.aigc.dit.generate.GenerateRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical {@link GenerateRequest} → RunningHub OpenAPI v2 字段。
 * 文生图/图生图验证期强制 resolution=1k、quality=low；官方通道才带 quality。
 * 文生视频按 seedance 2.0 合同翻译 duration/resolution/ratio，不改创作端字段。
 */
public final class RunningHubRequestMapper {

    static final String DEFAULT_ASPECT = "16:9";
    static final String DEFAULT_VIDEO_RATIO = "adaptive";
    static final String DEFAULT_VIDEO_RESOLUTION = "720p";
    /** 官方 i2i 合同：参考图 1–10 张。 */
    static final int MAX_I2I_IMAGES = 10;
    static final Set<String> ASPECTS = Set.of(
            "1:1", "1:2", "2:1", "1:3", "3:1", "2:3", "3:2", "3:4", "4:3",
            "4:5", "5:4", "9:16", "16:9", "21:9", "9:21");
    static final Set<String> VIDEO_RATIOS = Set.of(
            "adaptive", "16:9", "4:3", "1:1", "3:4", "9:16", "21:9");
    static final Set<String> VIDEO_RESOLUTIONS = Set.of(
            "480p", "720p", "native1080p", "native4k", "1080p", "2k", "4k");

    private RunningHubRequestMapper() {
    }

    public static Map<String, Object> toT2iBody(GenerateRequest request, String t2iPath,
                                                String forceResolution, String forceQuality) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", request.getPrompt() == null ? "" : request.getPrompt().trim());
        body.put("aspectRatio", aspectRatio(request.getRatio()));
        body.put("resolution", forceResolution == null || forceResolution.isBlank() ? "1k" : forceResolution.toLowerCase(Locale.ROOT));
        if (RunningHubUrls.officialChannel(t2iPath)) {
            body.put("quality", forceQuality == null || forceQuality.isBlank() ? "low" : forceQuality.toLowerCase(Locale.ROOT));
        }
        return body;
    }

    public static Map<String, Object> toI2iBody(GenerateRequest request, String i2iPath,
                                                List<String> imageUrls,
                                                String forceResolution, String forceQuality) {
        List<String> images = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", request.getPrompt() == null ? "" : request.getPrompt().trim());
        body.put("imageUrls", images);
        body.put("aspectRatio", aspectRatio(request.getRatio()));
        body.put("resolution", forceResolution == null || forceResolution.isBlank() ? "1k" : forceResolution.toLowerCase(Locale.ROOT));
        if (RunningHubUrls.officialChannel(i2iPath)) {
            body.put("quality", forceQuality == null || forceQuality.isBlank() ? "low" : forceQuality.toLowerCase(Locale.ROOT));
        }
        return body;
    }

    public static Map<String, Object> toT2vBody(GenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", request.getPrompt() == null ? "" : request.getPrompt().trim());
        body.put("resolution", videoResolution(request.getResolution()));
        body.put("duration", videoDuration(request.getDurationSec()));
        body.put("generateAudio", true);
        body.put("ratio", videoRatio(request.getRatio()));
        body.put("webSearch", false);
        body.put("returnLastFrame", false);
        return body;
    }

    public static Map<String, Object> toR2vBody(GenerateRequest request, List<String> imageUrls, List<String> videoUrls) {
        List<String> images = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        List<String> videos = videoUrls == null ? List.of() : List.copyOf(videoUrls);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", r2vPrompt(request.getPrompt(), images.size(), videos.size()));
        body.put("resolution", videoResolution(request.getResolution()));
        body.put("duration", videoDuration(request.getDurationSec()));
        if (!images.isEmpty()) {
            body.put("imageUrls", images);
        }
        if (!videos.isEmpty()) {
            body.put("videoUrls", videos);
        }
        body.put("generateAudio", true);
        body.put("ratio", videoRatio(request.getRatio()));
        body.put("realPersonMode", true);
        body.put("conversionSlots", List.of("all"));
        body.put("returnLastFrame", false);
        return body;
    }

    public static String t2iPath(GenerateRequest request, String fallback) {
        return path(request, "t2iPath", fallback);
    }

    public static String i2iPath(GenerateRequest request, String fallback) {
        return path(request, "i2iPath", fallback);
    }

    public static String t2vPath(GenerateRequest request, String fallback) {
        return path(request, "t2vPath", fallback);
    }

    public static String r2vPath(GenerateRequest request, String fallback) {
        return path(request, "r2vPath", fallback);
    }

    static String path(GenerateRequest request, String key, String fallback) {
        Object configured = request.getAdapterConfig() == null ? null : request.getAdapterConfig().get(key);
        if (configured != null && !String.valueOf(configured).isBlank()) {
            return String.valueOf(configured).trim();
        }
        return fallback;
    }

    static String aspectRatio(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return DEFAULT_ASPECT;
        }
        String normalized = ratio.trim().replace('：', ':');
        return ASPECTS.contains(normalized) ? normalized : DEFAULT_ASPECT;
    }

    static String videoRatio(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return DEFAULT_VIDEO_RATIO;
        }
        String normalized = ratio.trim().replace('：', ':');
        if ("自动".equals(normalized) || "auto".equalsIgnoreCase(normalized)) {
            return DEFAULT_VIDEO_RATIO;
        }
        return VIDEO_RATIOS.contains(normalized) ? normalized : DEFAULT_VIDEO_RATIO;
    }

    static String videoResolution(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return DEFAULT_VIDEO_RESOLUTION;
        }
        String normalized = resolution.trim().toLowerCase(Locale.ROOT);
        if ("1k".equals(normalized)) {
            return DEFAULT_VIDEO_RESOLUTION;
        }
        return VIDEO_RESOLUTIONS.contains(normalized) ? normalized : DEFAULT_VIDEO_RESOLUTION;
    }

    static String videoDuration(Integer durationSec) {
        int seconds = durationSec == null ? 5 : durationSec;
        if (seconds < 4) {
            seconds = 4;
        }
        if (seconds > 15) {
            seconds = 15;
        }
        return String.valueOf(seconds);
    }

    static String r2vPrompt(String prompt, int imageCount, int videoCount) {
        String text = prompt == null ? "" : prompt.trim();
        if (text.toLowerCase(Locale.ROOT).contains("@image") || text.toLowerCase(Locale.ROOT).contains("@video")) {
            return text;
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 1; i <= imageCount; i++) {
            if (!prefix.isEmpty()) {
                prefix.append(' ');
            }
            prefix.append("@Image ").append(i);
        }
        for (int i = 1; i <= videoCount; i++) {
            if (!prefix.isEmpty()) {
                prefix.append(' ');
            }
            prefix.append("@Video ").append(i);
        }
        if (prefix.isEmpty()) {
            return text;
        }
        return text.isBlank() ? prefix.toString() : prefix + " " + text;
    }
}
