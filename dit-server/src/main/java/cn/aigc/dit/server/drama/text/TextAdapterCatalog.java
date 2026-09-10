package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文本通道适配协议。运输层是 OpenAI 兼容 chat HTTP，
 * <strong>不</strong>注册进 {@link AdapterCatalog}，也不进 GenerateWorker。
 */
public final class TextAdapterCatalog {

    public static final String AGNES_TEXT = "agnes-text";
    public static final String OPENAI_COMPAT = "openai-compat";

    /** 与 Agnes 图/视频同一官方地址；对话模型名按 Agnes 文档 {@code agnes-2.5-flash}。 */
    public static final String AGNES_DEFAULT_ENDPOINT = AdapterCatalog.AGNES_DEFAULT_ENDPOINT;
    public static final String AGNES_CHAT_MODEL = "agnes-2.5-flash";

    public static final String PROVIDER_OFFICIAL = AdapterCatalog.PROVIDER_OFFICIAL;
    public static final String PROVIDER_CUSTOM = "自定义配置";

    private static final List<Spec> ALL = List.of(
            new Spec(AGNES_TEXT, "Agnes 对话", PROVIDER_OFFICIAL, AGNES_DEFAULT_ENDPOINT, AGNES_CHAT_MODEL),
            new Spec(OPENAI_COMPAT, "OpenAI 兼容", PROVIDER_CUSTOM, "", "")
    );

    private TextAdapterCatalog() {
    }

    public static List<Spec> adminVisible() {
        return new ArrayList<>(ALL);
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
            throw new BizException(ErrorCode.BAD_REQUEST, "未知或不支持的文本适配协议: " + adapterKey);
        }
        return spec;
    }

    public static String resolveKey(String adapterKey, String provider, String modelKey) {
        Spec spec = find(adapterKey);
        if (spec != null) {
            return spec.key();
        }
        return infer(provider, modelKey);
    }

    public static String infer(String provider, String modelKey) {
        String key = modelKey == null ? "" : modelKey.toLowerCase(Locale.ROOT);
        String prov = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        if (key.contains("openai") || prov.contains("openai") || prov.contains("自定义") || prov.contains("手动")) {
            return OPENAI_COMPAT;
        }
        if (key.contains("agnes") || prov.contains("官方") || prov.contains("agnes") || prov.contains("official")) {
            return AGNES_TEXT;
        }
        if (key.contains("gpt") || key.contains("compat")) {
            return OPENAI_COMPAT;
        }
        return null;
    }

    public static Map<String, Object> toAdminView(Spec spec) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", spec.key());
        row.put("label", spec.label());
        row.put("category", ModelCategory.TEXT);
        row.put("provider", spec.providerLabel());
        row.put("defaultEndpoint", spec.defaultEndpoint());
        row.put("modes", List.of());
        row.put("defaultPaths", Map.of());
        row.put("defaultModelKey", spec.defaultModelKey());
        return row;
    }

    public record Spec(
            String key,
            String label,
            String providerLabel,
            String defaultEndpoint,
            String defaultModelKey
    ) {
    }
}
