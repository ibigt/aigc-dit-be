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
    public static final String GPT_IMAGE_2_0 = "gpt-image-2.0";
    public static final String GPT_IMAGE_2_5 = "gpt-image-2.5";
    public static final String SEEDREAM_4_0 = "seedream-4.0";
    public static final String SEEDREAM_4_5 = "seedream-4.5";
    public static final String SEEDREAM_5_0 = "seedream-5.0";
    public static final String SEEDANCE_2 = "seedance-2";
    public static final String SEEDANCE_2_0 = "seedance-2.0";
    public static final String SEEDANCE_2_5 = "seedance-2.5";
    public static final String MINIMAX_H3 = "minimax-h3";
    public static final String AGNES_IMAGE_2_5 = "agnes-image-2.5";
    public static final String AGNES_VIDEO_2_5 = "agnes-video-2.5";
    public static final String MINIMAX_SPEECH = "minimax-speech";
    public static final String OPENAI_SPEECH = "openai-speech";
    public static final String MOCK = "mock";

    public static final String TRANSPORT_RUNNINGHUB = "runninghub";
    public static final String TRANSPORT_COMFYUI = "comfyui";
    public static final String TRANSPORT_AGNES = "agnes";
    public static final String TRANSPORT_ARK = "ark";
    public static final String TRANSPORT_SEEDREAM = "seedream";
    public static final String TRANSPORT_MINIMAX = "minimax";
    public static final String TRANSPORT_MINIMAX_SPEECH = "minimax-speech";
    public static final String TRANSPORT_OPENAI_SPEECH = "openai-speech";
    public static final String TRANSPORT_OPENAI_IMAGE = "openai-image";
    public static final String TRANSPORT_MOCK = "mock";

    public static final String PROVIDER_OFFICIAL = "官方API";
    public static final String PROVIDER_OFFICIAL_TOKEN_PLAN = "官方API（token plan）";
    public static final String PROVIDER_COMFYUI = "ComfyUI";
    public static final String AGNES_DEFAULT_ENDPOINT = "https://api.agnes-ai.cn/v1";
    public static final String AGNES_IMAGE_MODEL = "agnes-image-2.5-flash";
    public static final String AGNES_VIDEO_MODEL = "agnes-video-2.5-flash";
    public static final String ARK_DEFAULT_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3";
    public static final String SEEDANCE_2_0_MODEL = "doubao-seedance-2-0-260128";
    public static final String SEEDANCE_2_5_MODEL = "doubao-seedance-2-5-260628";
    public static final String MINIMAX_DEFAULT_ENDPOINT = "https://api.minimax.cn";
    public static final String MINIMAX_COMFY_ENDPOINT = "http://127.0.0.1:8188";
    public static final String MINIMAX_H3_MODEL = "MiniMax-H3";
    public static final String MINIMAX_H3_MAX_MODEL = "MiniMax-H3-Max";
    public static final String MINIMAX_SPEECH_DEFAULT_ENDPOINT = "https://api.minimax.cn/v1/t2a_v2";
    public static final String MINIMAX_SPEECH_DEFAULT_MODEL = "speech-2.8-hd";
    public static final String OPENAI_SPEECH_DEFAULT_ENDPOINT = "https://api.openai.com/v1";
    public static final String OPENAI_SPEECH_DEFAULT_MODEL = "gpt-4o-mini-tts";
    public static final String OPENAI_IMAGE_DEFAULT_ENDPOINT = "https://api.openai.com/v1";
    public static final String GPT_IMAGE_2_0_MODEL = "gpt-image-2";
    public static final String GPT_IMAGE_2_5_MODEL = "gpt-image-2.5-sunburst";
    public static final String SEEDREAM_4_0_MODEL = "doubao-seedream-4-0-250828";
    public static final String SEEDREAM_4_5_MODEL = "doubao-seedream-4-5-251128";
    /** Token Plan 图片模型。Lite 已对新用户关闭，默认用控制台里的 Pro。 */
    public static final String SEEDREAM_5_0_MODEL = "doubao-seedream-5-0-pro";

    private static final List<Spec> ALL = List.of(
            new Spec(GPT_IMAGE_2, "GPT Image 2", ModelCategory.IMAGE, TRANSPORT_RUNNINGHUB, "RunningHub",
                    "https://www.runninghub.ai", List.of(GenerateMode.T2I, GenerateMode.I2I),
                    Map.of(
                            "t2iPath", "rhart-image-g-2/text-to-image",
                            "i2iPath", "rhart-image-g-2/image-to-image"), true),
            new Spec(GPT_IMAGE_2_0, "GPT Image 2.0", ModelCategory.IMAGE, TRANSPORT_OPENAI_IMAGE, PROVIDER_OFFICIAL,
                    OPENAI_IMAGE_DEFAULT_ENDPOINT, List.of(GenerateMode.T2I, GenerateMode.I2I),
                    Map.of(), true),
            new Spec(GPT_IMAGE_2_5, "GPT Image 2.5", ModelCategory.IMAGE, TRANSPORT_OPENAI_IMAGE, PROVIDER_OFFICIAL,
                    OPENAI_IMAGE_DEFAULT_ENDPOINT, List.of(GenerateMode.T2I, GenerateMode.I2I),
                    Map.of(), true),
            new Spec(SEEDREAM_4_0, "Seedream 4.0", ModelCategory.IMAGE, TRANSPORT_SEEDREAM, PROVIDER_OFFICIAL,
                    ARK_DEFAULT_ENDPOINT, List.of(GenerateMode.T2I, GenerateMode.I2I),
                    Map.of(), true),
            new Spec(SEEDREAM_4_5, "Seedream 4.5", ModelCategory.IMAGE, TRANSPORT_SEEDREAM, PROVIDER_OFFICIAL,
                    ARK_DEFAULT_ENDPOINT, List.of(GenerateMode.T2I, GenerateMode.I2I),
                    Map.of(), true),
            new Spec(SEEDREAM_5_0, "Seedream 5.0", ModelCategory.IMAGE, TRANSPORT_SEEDREAM, PROVIDER_OFFICIAL_TOKEN_PLAN,
                    ARK_DEFAULT_ENDPOINT, List.of(GenerateMode.T2I, GenerateMode.I2I),
                    Map.of(), true),
            new Spec(SEEDANCE_2, "Seedance 2.x", ModelCategory.VIDEO, TRANSPORT_RUNNINGHUB, "RunningHub",
                    "https://www.runninghub.ai", List.of(GenerateMode.T2V, GenerateMode.R2V),
                    Map.of(
                            "t2vPath", "rhart-video/sparkvideo-2.0/text-to-video",
                            "r2vPath", "rhart-video/sparkvideo-2.0/multimodal-video"), true),
            new Spec(SEEDANCE_2_0, "Seedance 2.0", ModelCategory.VIDEO, TRANSPORT_ARK, PROVIDER_OFFICIAL,
                    ARK_DEFAULT_ENDPOINT, List.of(GenerateMode.T2V, GenerateMode.R2V, GenerateMode.I2V),
                    Map.of(), true),
            new Spec(SEEDANCE_2_5, "Seedance 2.5", ModelCategory.VIDEO, TRANSPORT_ARK, PROVIDER_OFFICIAL,
                    ARK_DEFAULT_ENDPOINT, List.of(GenerateMode.T2V, GenerateMode.R2V, GenerateMode.I2V),
                    Map.of(), true),
            new Spec(MINIMAX_H3, "MiniMax-H3", ModelCategory.VIDEO, TRANSPORT_MINIMAX, PROVIDER_OFFICIAL,
                    MINIMAX_DEFAULT_ENDPOINT, List.of(GenerateMode.T2V, GenerateMode.R2V, GenerateMode.I2V),
                    Map.of(), true),
            new Spec(AGNES_IMAGE_2_5, "Agnes Image 2.5", ModelCategory.IMAGE, TRANSPORT_AGNES, PROVIDER_OFFICIAL,
                    AGNES_DEFAULT_ENDPOINT, List.of(GenerateMode.T2I, GenerateMode.I2I),
                    Map.of(), true),
            new Spec(AGNES_VIDEO_2_5, "Agnes Video 2.5", ModelCategory.VIDEO, TRANSPORT_AGNES, PROVIDER_OFFICIAL,
                    AGNES_DEFAULT_ENDPOINT, List.of(GenerateMode.T2V, GenerateMode.R2V, GenerateMode.I2V),
                    Map.of(), true),
            new Spec(MINIMAX_SPEECH, "MiniMax Speech", ModelCategory.AUDIO, TRANSPORT_MINIMAX_SPEECH, PROVIDER_OFFICIAL,
                    MINIMAX_SPEECH_DEFAULT_ENDPOINT, List.of(GenerateMode.T2A), Map.of(), true),
            new Spec(OPENAI_SPEECH, "OpenAI 兼容语音", ModelCategory.AUDIO, TRANSPORT_OPENAI_SPEECH, "自定义配置",
                    OPENAI_SPEECH_DEFAULT_ENDPOINT, List.of(GenerateMode.T2A), Map.of(), true),
            new Spec(MOCK, "Mock 占位", null, TRANSPORT_MOCK, "mock",
                    "/mock-api", List.of(GenerateMode.T2I, GenerateMode.I2I, GenerateMode.T2V, GenerateMode.R2V, GenerateMode.I2V, GenerateMode.T2A),
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
        if (adapterKey != null && !adapterKey.isBlank()) {
            Spec spec = find(adapterKey);
            if (spec == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未知或不支持的适配协议: " + adapterKey);
            }
            return spec.key();
        }
        return infer(provider, category, modelKey);
    }

    /**
     * 出片选路：官方 Seedance 模型标识若被配成 Agnes / 空协议，强制回到 Ark。
     * RunningHub 的 {@code seedance-2} 不改。
     */
    public static String resolveKeyForGenerate(String adapterKey, String provider, String category, String modelKey) {
        String resolved = resolveKey(adapterKey, provider, category, modelKey);
        String official = officialSeedanceAdapter(modelKey);
        if (official != null && !isArk(resolved) && !isRunningHub(resolved)) {
            return official;
        }
        return resolved;
    }

    public static String infer(String provider, String category, String modelKey) {
        String key = modelKey == null ? "" : modelKey.toLowerCase(Locale.ROOT);
        String prov = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        String cat = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (key.contains("agnes-image") || (key.contains("agnes") && ModelCategory.IMAGE.equals(cat))) {
            return AGNES_IMAGE_2_5;
        }
        if (isArk25Model(key)) {
            return SEEDANCE_2_5;
        }
        if (isArk20Model(key) || (isArkProvider(prov) && ModelCategory.VIDEO.equals(cat))) {
            return SEEDANCE_2_0;
        }
        if (isOfficialTokenPlan(provider) && ModelCategory.VIDEO.equals(cat)
                && (key.contains("doubao-seedance") || key.contains("seedance-2.0") || key.contains("seedance-2-0"))) {
            return SEEDANCE_2_0;
        }
        if (key.contains("agnes")) {
            return AGNES_VIDEO_2_5;
        }
        if (ModelCategory.AUDIO.equals(cat) && (key.startsWith("speech-")
                || key.contains("minimax-speech") || prov.contains("minimax") || prov.contains("稀宇"))) {
            return MINIMAX_SPEECH;
        }
        if (ModelCategory.VIDEO.equals(cat)
                && (key.contains("minimax") || key.contains("hailuo") || key.contains("mini-max"))) {
            return MINIMAX_H3;
        }
        if (key.contains("seedance") || (prov.contains("running") && ModelCategory.VIDEO.equals(cat))) {
            return SEEDANCE_2;
        }
        if (ModelCategory.IMAGE.equals(cat) && !prov.contains("running") && isSeedream50Model(key)) {
            return SEEDREAM_5_0;
        }
        if (ModelCategory.IMAGE.equals(cat) && !prov.contains("running") && isSeedream45Model(key)) {
            return SEEDREAM_4_5;
        }
        if (ModelCategory.IMAGE.equals(cat) && !prov.contains("running") && isSeedream40Model(key)) {
            return SEEDREAM_4_0;
        }
        if (ModelCategory.IMAGE.equals(cat)
                && (key.contains("gpt-image-2.5") || key.contains("gpt-image-2-5")
                || key.contains("2.5-sunburst") || key.contains("2.5-flare"))) {
            return GPT_IMAGE_2_5;
        }
        if (ModelCategory.IMAGE.equals(cat)
                && (key.contains("gpt-image-2.0") || key.contains("gpt-image-2-0")
                || ((prov.contains("官方") || prov.contains("openai") || prov.contains("open ai"))
                && (key.equals("gpt-image-2") || key.startsWith("gpt-image-2"))))) {
            return GPT_IMAGE_2_0;
        }
        if (key.contains("gpt-image") || (prov.contains("running") && ModelCategory.IMAGE.equals(cat))) {
            return GPT_IMAGE_2;
        }
        if (ModelCategory.AUDIO.equals(cat)
                && (key.contains("tts") || key.contains("speech") || prov.contains("openai") || prov.contains("自定义"))) {
            return OPENAI_SPEECH;
        }
        if (prov.contains("mock") || key.startsWith("mock")) {
            return MOCK;
        }
        return null;
    }

    public static String transport(String adapterKey, String provider) {
        String key = resolveKey(adapterKey, provider, null, null);
        if (MINIMAX_H3.equals(key) && isComfyUiProvider(provider)) {
            return TRANSPORT_COMFYUI;
        }
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

    public static boolean isArk(String adapterKey) {
        Spec spec = find(adapterKey);
        return spec != null && TRANSPORT_ARK.equals(spec.transport());
    }

    public static boolean isMiniMax(String adapterKey) {
        Spec spec = find(adapterKey);
        return spec != null && MINIMAX_H3.equals(spec.key());
    }

    public static boolean isOpenAiSpeech(String adapterKey) {
        Spec spec = find(adapterKey);
        return spec != null && OPENAI_SPEECH.equals(spec.key());
    }

    public static boolean isOpenAiImage(String adapterKey) {
        Spec spec = find(adapterKey);
        return spec != null && TRANSPORT_OPENAI_IMAGE.equals(spec.transport());
    }

    public static boolean isSeedream(String adapterKey) {
        Spec spec = find(adapterKey);
        return spec != null && TRANSPORT_SEEDREAM.equals(spec.transport());
    }

    public static boolean isMiniMaxSpeech(String adapterKey) {
        Spec spec = find(adapterKey);
        return spec != null && MINIMAX_SPEECH.equals(spec.key());
    }

    public static boolean isMiniMaxOfficial(String adapterKey, String provider) {
        return isMiniMax(adapterKey) && !isComfyUiProvider(provider);
    }

    public static boolean isComfyUiProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        return provider.toLowerCase(Locale.ROOT).contains("comfy");
    }

    /** 模型标识是火山官方 Seedance，不能再落到 Agnes / RunningHub。 */
    public static boolean looksLikeOfficialSeedance(String modelKey) {
        String key = modelKey == null ? "" : modelKey.toLowerCase(Locale.ROOT);
        return isArk20Model(key) || isArk25Model(key);
    }

    public static String officialSeedanceAdapter(String modelKey) {
        String key = modelKey == null ? "" : modelKey.toLowerCase(Locale.ROOT);
        if (isArk25Model(key)) {
            return SEEDANCE_2_5;
        }
        if (isArk20Model(key)) {
            return SEEDANCE_2_0;
        }
        return null;
    }

    public static boolean isOfficialTokenPlan(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String value = provider.toLowerCase(Locale.ROOT);
        return value.contains("token plan") || value.contains("tokenplan") || value.contains("agent plan");
    }

    public static String officialProviderOf(String requested, String adapterKey) {
        if (MINIMAX_H3.equals(adapterKey) || MINIMAX_SPEECH.equals(adapterKey)) {
            return isComfyUiProvider(requested) ? PROVIDER_COMFYUI : PROVIDER_OFFICIAL;
        }
        if ((isArk(adapterKey) || isSeedream(adapterKey)) && isOfficialTokenPlan(requested)) {
            return PROVIDER_OFFICIAL_TOKEN_PLAN;
        }
        Spec spec = find(adapterKey);
        return spec != null ? spec.providerLabel() : requested;
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
        AudioReferenceCapabilities audio = AudioReferenceCapabilities.resolve(
                spec.key(), spec.providerLabel(), spec.category(), defaultModelKey(spec.key()));
        row.put("supportsAudioReference", audio.supported());
        row.put("maxAudioReferences", audio.maxReferences());
        row.put("supportsAudioOnlyReference", audio.audioOnly());
        return row;
    }

    public static String defaultModelKey(String adapterKey) {
        if (AGNES_IMAGE_2_5.equals(adapterKey)) {
            return AGNES_IMAGE_MODEL;
        }
        if (AGNES_VIDEO_2_5.equals(adapterKey)) {
            return AGNES_VIDEO_MODEL;
        }
        if (SEEDANCE_2_0.equals(adapterKey)) {
            return SEEDANCE_2_0_MODEL;
        }
        if (SEEDANCE_2_5.equals(adapterKey)) {
            return SEEDANCE_2_5_MODEL;
        }
        if (MINIMAX_H3.equals(adapterKey)) {
            return MINIMAX_H3_MODEL;
        }
        if (MINIMAX_SPEECH.equals(adapterKey)) {
            return MINIMAX_SPEECH_DEFAULT_MODEL;
        }
        if (OPENAI_SPEECH.equals(adapterKey)) {
            return OPENAI_SPEECH_DEFAULT_MODEL;
        }
        if (GPT_IMAGE_2_0.equals(adapterKey)) {
            return GPT_IMAGE_2_0_MODEL;
        }
        if (GPT_IMAGE_2_5.equals(adapterKey)) {
            return GPT_IMAGE_2_5_MODEL;
        }
        if (SEEDREAM_4_0.equals(adapterKey)) {
            return SEEDREAM_4_0_MODEL;
        }
        if (SEEDREAM_4_5.equals(adapterKey)) {
            return SEEDREAM_4_5_MODEL;
        }
        if (SEEDREAM_5_0.equals(adapterKey)) {
            return SEEDREAM_5_0_MODEL;
        }
        return "";
    }

    private static boolean isArkProvider(String provider) {
        return provider.contains("ark")
                || provider.contains("volc")
                || provider.contains("火山")
                || provider.contains("方舟")
                || provider.contains("byteplus");
    }

    private static boolean isArk25Model(String modelKey) {
        return modelKey.contains("seedance-2.5")
                || modelKey.contains("seedance-2-5")
                || modelKey.contains("doubao-seedance-2-5")
                || modelKey.contains("doubao-seedance-2.5");
    }

    private static boolean isArk20Model(String modelKey) {
        return modelKey.contains("doubao-seedance-2-0")
                || modelKey.contains("doubao-seedance-2.0")
                || modelKey.contains("seedance-2-0-fast")
                || modelKey.contains("seedance-2.0-fast");
    }

    private static boolean isSeedream50Model(String modelKey) {
        return modelKey.contains("seedream-5")
                || modelKey.contains("seedream_5")
                || modelKey.contains("doubao-seedream-5");
    }

    private static boolean isSeedream45Model(String modelKey) {
        return modelKey.contains("seedream-4.5")
                || modelKey.contains("seedream-4-5")
                || modelKey.contains("doubao-seedream-4-5")
                || modelKey.contains("doubao-seedream-4.5");
    }

    private static boolean isSeedream40Model(String modelKey) {
        return modelKey.contains("seedream-4.0")
                || modelKey.contains("seedream-4-0")
                || modelKey.contains("doubao-seedream-4-0")
                || modelKey.contains("doubao-seedream-4.0");
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
