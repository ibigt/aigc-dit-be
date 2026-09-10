package cn.aigc.dit.generate;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 已实现的适配协议（出站翻译包）。key 不带来源前缀；运输层由本目录派生。
 */
public final class AdapterCatalog {

    public static final String GPT_IMAGE_2 = "gpt-image-2";
    public static final String SEEDANCE_2 = "seedance-2";
    public static final String MINIMAX_H3 = "minimax-h3";
    public static final String AGNES_IMAGE_2_5 = "agnes-image-2.5";
    public static final String AGNES_VIDEO_2_5 = "agnes-video-2.5";
    public static final String MOCK = "mock";

    public static final String TRANSPORT_RUNNINGHUB = "runninghub";
    public static final String TRANSPORT_COMFYUI = "comfyui";
    public static final String TRANSPORT_AGNES = "agnes";
    public static final String TRANSPORT_MOCK = "mock";

    public static final String PROVIDER_OFFICIAL = "官方API";
    public static final String AGNES_DEFAULT_ENDPOINT = "https://api.agnes-ai.cn/v1";
    public static final String AGNES_IMAGE_MODEL = "agnes-image-2.5-flash";
    public static final String AGNES_VIDEO_MODEL = "agnes-video-2.5-flash";

    private static final List<Spec> ALL = List.of(
            new Spec(GPT_IMAGE_2, "GPT Image 2", ModelCategory.IMAGE, TRANSPORT_RUNNINGHUB, "RunningHub",
                    "https://www.runninghub.ai", List.of(GenerateMode.T2I),
                    Map.of("t2iPath", "rhart-image-g-2/text-to-image"), true),
            new Spec(SEEDANCE_2, "Seedance 2.x", ModelCategory.VIDEO, TRANSPORT_RUNNINGHUB, "RunningHub",
                    "https://www.runninghub.ai", List.of(GenerateMode.T2V, GenerateMode.R2V),
                    Map.of(
                            "t2vPath", "rhart-video/sparkvideo-2.0/text-to-video",
                            "r2vPath", "rhart-video/sparkvideo-2.0/multimodal-video"), true),
            new Spec(MINIMAX_H3, "MiniMax-H3", ModelCategory.VIDEO, TRANSPORT_COMFYUI, "ComfyUI",
                    "http://127.0.0.1:8188", List.of(GenerateMode.T2V, GenerateMode.R2V, GenerateMode.I2V),
                    Map.of(), true),
            new Spec(AGNES_IMAGE_2_5, "Agnes Image 2.5", ModelCategory.IMAGE, TRANSPORT_AGNES, PROVIDER_OFFICIAL,
                    AGNES_DEFAULT_ENDPOINT, List.of(GenerateMode.T2I, GenerateMode.I2I),
                    Map.of(), true),
            new Spec(AGNES_VIDEO_2_5, "Agnes Video 2.5", ModelCategory.VIDEO, TRANSPORT_AGNES, PROVIDER_OFFICIAL,
                    AGNES_DEFAULT_ENDPOINT, List.of(GenerateMode.T2V, GenerateMode.R2V, GenerateMode.I2V),
                    Map.of(), true),
            new Spec(MOCK, "Mock 占位", null, TRANSPORT_MOCK, "mock",
                    "/mock-api", List.of(GenerateMode.T2I, GenerateMode.I2I, GenerateMode.T2V, GenerateMode.R2V, GenerateMode.I2V),
                    Map.of(), false)
    );

    private AdapterCatalog() {
    }

    public static List<Spec> adminVisible() {
        List<Spec> values = new ArrayList<>();
        for (Spec spec : ALL) {
            if (spec.adminVisible()) {
                values.add(spec);
            }
        }
        return values;
    }

    public static Spec find(String adapterKey) {
        if (adapterKey == null || adapterKey.isBlank()) {
            return null;
        }
        String key = adapterKey.trim().toLowerCase(Locale.ROOT);
        for (Spec spec : ALL) {
            if (spec.key().equals(key)) {
                return spec;
            }
        }
        return null;
    }

    public static Spec require(String adapterKey) {
        Spec spec = find(adapterKey);
        if (spec == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知或不支持的适配协议: " + adapterKey);
        }
        return spec;
    }

    public static String resolveKey(String adapterKey, String provider, String category, String modelKey) {
        Spec spec = find(adapterKey);
        if (spec != null) {
            return spec.key();
        }
        return infer(provider, category, modelKey);
    }

    public static String infer(String provider, String category, String modelKey) {
        String key = modelKey == null ? "" : modelKey.toLowerCase(Locale.ROOT);
        String prov = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        String cat = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (key.contains("agnes-image") || (isOfficialProvider(prov) && ModelCategory.IMAGE.equals(cat))) {
            return AGNES_IMAGE_2_5;
        }
        if (key.contains("agnes") || (isOfficialProvider(prov) && ModelCategory.VIDEO.equals(cat))) {
            return AGNES_VIDEO_2_5;
        }
        if (key.contains("minimax") || (prov.contains("comfy") && ModelCategory.VIDEO.equals(cat))) {
            return MINIMAX_H3;
        }
        if (key.contains("seedance") || (prov.contains("running") && ModelCategory.VIDEO.equals(cat))) {
            return SEEDANCE_2;
        }
        if (key.contains("gpt-image") || (prov.contains("running") && ModelCategory.IMAGE.equals(cat))) {
            return GPT_IMAGE_2;
        }
        if (prov.contains("mock") || key.startsWith("mock")) {
            return MOCK;
        }
        return null;
    }

    public static String transport(String adapterKey, String provider) {
        String key = resolveKey(adapterKey, provider, null, null);
        Spec spec = find(key);
        if (spec != null) {
            return spec.transport();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "未配置适配协议，请在管理端选择已接入的适配协议");
    }

    public static boolean supportsMode(String adapterKey, String mode) {
        Spec spec = find(adapterKey);
        if (spec == null) {
            return true;
        }
        String canonical = GenerateMode.canonical(mode);
        for (String item : spec.modes()) {
            if (item.equalsIgnoreCase(canonical)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRunningHub(String adapterKey) {
        Spec spec = find(adapterKey);
        return spec != null && TRANSPORT_RUNNINGHUB.equals(spec.transport());
    }

    public static boolean isAgnes(String adapterKey) {
        Spec spec = find(adapterKey);
        return spec != null && TRANSPORT_AGNES.equals(spec.transport());
    }

    public static List<String> constrainCapabilities(List<String> capabilities, String category, Spec spec) {
        List<String> requested = GenerateMode.canonicalizeCapabilities(capabilities);
        List<String> values = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            values.add(category);
        }
        if (spec == null) {
            if (requested != null) {
                for (String item : requested) {
                    if (!containsIgnoreCase(values, item)) {
                        values.add(item);
                    }
                }
            }
            return values;
        }
        if (requested != null) {
            for (String item : requested) {
                if (containsIgnoreCase(spec.modes(), item) && !containsIgnoreCase(values, item)) {
                    values.add(item);
                }
            }
        }
        boolean hasMode = false;
        for (String item : values) {
            if (GenerateMode.isSpecificMode(item)) {
                hasMode = true;
                break;
            }
        }
        if (!hasMode) {
            for (String mode : spec.modes()) {
                if (!containsIgnoreCase(values, mode)) {
                    values.add(mode);
                }
            }
        }
        return values;
    }

    public static Map<String, Object> toAdminView(Spec spec) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", spec.key());
        row.put("label", spec.label());
        row.put("category", spec.category());
        row.put("provider", spec.providerLabel());
        row.put("defaultEndpoint", spec.defaultEndpoint());
        row.put("modes", spec.modes());
        row.put("defaultPaths", spec.defaultPaths());
        row.put("defaultModelKey", defaultModelKey(spec.key()));
        return row;
    }

    public static String defaultModelKey(String adapterKey) {
        if (AGNES_IMAGE_2_5.equals(adapterKey)) {
            return AGNES_IMAGE_MODEL;
        }
        if (AGNES_VIDEO_2_5.equals(adapterKey)) {
            return AGNES_VIDEO_MODEL;
        }
        return "";
    }

    private static boolean isOfficialProvider(String provider) {
        return provider.contains("官方")
                || provider.contains("agnes")
                || provider.contains("official");
    }

    private static boolean containsIgnoreCase(List<String> values, String wanted) {
        if (values == null || wanted == null) {
            return false;
        }
        for (String item : values) {
            if (wanted.equalsIgnoreCase(item)) {
                return true;
            }
        }
        return false;
    }

    public record Spec(
            String key,
            String label,
            String category,
            String transport,
            String providerLabel,
            String defaultEndpoint,
            List<String> modes,
            Map<String, String> defaultPaths,
            boolean adminVisible
    ) {
    }
}
