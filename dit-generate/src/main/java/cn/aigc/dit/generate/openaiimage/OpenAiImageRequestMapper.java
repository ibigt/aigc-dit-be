package cn.aigc.dit.generate.openaiimage;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateRequest;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical {@link GenerateRequest} → OpenAI Images 官方字段。
 * {@code gpt-image-2.0}/{@code gpt-image-2.5} 共用同一请求形状，仅 model 不同。
 */
public final class OpenAiImageRequestMapper {

    public static final int MAX_I2I_IMAGES = 5;

    private OpenAiImageRequestMapper() {
    }

    public static String modelName(GenerateRequest request) {
        if (request.getModelKey() != null && !request.getModelKey().isBlank()) {
            return request.getModelKey().trim();
        }
        String adapter = request.getAdapterKey() == null ? "" : request.getAdapterKey().trim();
        if (AdapterCatalog.GPT_IMAGE_2_5.equalsIgnoreCase(adapter)) {
            return AdapterCatalog.GPT_IMAGE_2_5_MODEL;
        }
        return AdapterCatalog.GPT_IMAGE_2_0_MODEL;
    }

    public static Map<String, Object> toGenerateBody(GenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName(request));
        body.put("prompt", safePrompt(request.getPrompt()));
        body.put("size", imageSize(request.getRatio()));
        body.put("quality", imageQuality(request.getResolution(), modelName(request)));
        body.put("n", 1);
        return body;
    }

    public static Map<String, String> toEditFields(GenerateRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", modelName(request));
        fields.put("prompt", safePrompt(request.getPrompt()));
        fields.put("size", imageSize(request.getRatio()));
        fields.put("quality", imageQuality(request.getResolution(), modelName(request)));
        fields.put("n", "1");
        return fields;
    }

    static String imageSize(String ratio) {
        String value = ratio == null ? "" : ratio.trim();
        if ("1:1".equals(value)) {
            return "1024x1024";
        }
        if ("16:9".equals(value) || "3:2".equals(value) || "4:3".equals(value) || "21:9".equals(value)) {
            return "1536x1024";
        }
        if ("9:16".equals(value) || "2:3".equals(value) || "3:4".equals(value)) {
            return "1024x1536";
        }
        return "auto";
    }

    static String imageQuality(String resolution, String model) {
        String res = resolution == null ? "" : resolution.trim().toUpperCase(Locale.ROOT);
        boolean supportsMax = model != null && model.toLowerCase(Locale.ROOT).contains("2.5");
        if ("2K".equals(res) || "3K".equals(res) || "4K".equals(res)) {
            return supportsMax ? "high" : "high";
        }
        if ("1K".equals(res) || "720P".equals(res) || "480P".equals(res)) {
            return "medium";
        }
        return "auto";
    }

    static String safePrompt(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }
}
