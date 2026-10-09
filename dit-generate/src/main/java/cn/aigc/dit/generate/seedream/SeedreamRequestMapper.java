package cn.aigc.dit.generate.seedream;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical {@link GenerateRequest} → 方舟 Seedream {@code /images/generations} 字段。
 * 4.0 / 4.5 / 5.0 lite 支持组图与流式；5.0 pro / flash 只出单图，这两个参数传了会被拒绝。
 */
public final class SeedreamRequestMapper {

    /** 官方 4.x 多图融合上限约 10 张。 */
    public static final int MAX_I2I_IMAGES = 10;

    private SeedreamRequestMapper() {
    }

    public static String modelName(GenerateRequest request) {
        if (request.getModelKey() != null && !request.getModelKey().isBlank()) {
            return request.getModelKey().trim();
        }
        String adapter = request.getAdapterKey() == null ? "" : request.getAdapterKey().trim();
        if (AdapterCatalog.SEEDREAM_5_0.equalsIgnoreCase(adapter)) {
            return AdapterCatalog.SEEDREAM_5_0_MODEL;
        }
        if (AdapterCatalog.SEEDREAM_4_5.equalsIgnoreCase(adapter)) {
            return AdapterCatalog.SEEDREAM_4_5_MODEL;
        }
        return AdapterCatalog.SEEDREAM_4_0_MODEL;
    }

    public static Map<String, Object> toBody(GenerateRequest request, List<String> images) {
        String model = modelName(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", safePrompt(request.getPrompt()));
        body.put("size", imageSize(request.getResolution(), request.getRatio()));
        if (supportsGroupAndStream(model)) {
            body.put("sequential_image_generation", "disabled");
            body.put("stream", false);
        }
        body.put("response_format", "url");
        body.put("watermark", false);
        if (images != null && !images.isEmpty()) {
            if (images.size() == 1) {
                body.put("image", images.get(0));
            } else {
                body.put("image", images);
            }
        }
        return body;
    }

    /**
     * 5.0 pro / flash 不支持组图和流式。带上 {@code sequential_image_generation} 或 {@code stream}
     * 时，方舟会 400：parameter is not supported by the current model。
     */
    static boolean supportsGroupAndStream(String model) {
        String key = model == null ? "" : model.toLowerCase(Locale.ROOT);
        boolean seedream5 = key.contains("seedream-5");
        boolean singleImageOnly = key.contains("pro") || key.contains("flash");
        return !(seedream5 && singleImageOnly);
    }

    static String imageSize(String resolution, String ratio) {
        String res = resolution == null ? "" : resolution.trim().toUpperCase(Locale.ROOT);
        boolean fourK = "4K".equals(res) || "3K".equals(res);
        String mapped = pixelSize(ratio, fourK);
        if (mapped != null) {
            return mapped;
        }
        return fourK ? "4K" : "2K";
    }

    private static String pixelSize(String ratio, boolean fourK) {
        String value = ratio == null ? "" : ratio.trim();
        if (value.isEmpty()) {
            return null;
        }
        return switch (value) {
            case "1:1" -> fourK ? "4096x4096" : "2048x2048";
            case "16:9" -> fourK ? "3840x2160" : "2560x1440";
            case "9:16" -> fourK ? "2160x3840" : "1440x2560";
            case "4:3" -> fourK ? "3840x2880" : "2304x1728";
            case "3:4" -> fourK ? "2880x3840" : "1728x2304";
            case "3:2" -> fourK ? "3840x2560" : "2496x1664";
            case "2:3" -> fourK ? "2560x3840" : "1664x2496";
            case "21:9" -> fourK ? "4096x1744" : "3024x1296";
            default -> null;
        };
    }

    static String safePrompt(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }
}
