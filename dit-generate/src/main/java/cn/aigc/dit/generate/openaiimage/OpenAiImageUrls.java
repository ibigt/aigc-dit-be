package cn.aigc.dit.generate.openaiimage;

import cn.aigc.dit.generate.AdapterCatalog;

import java.util.Locale;

/**
 * OpenAI Images 官方地址。
 */
public final class OpenAiImageUrls {

    private OpenAiImageUrls() {
    }

    public static String v1Base(String endpoint, String fallback) {
        String base = firstNonBlank(endpoint, fallback, AdapterCatalog.OPENAI_IMAGE_DEFAULT_ENDPOINT);
        base = stripTrailingSlash(base);
        if (base.endsWith("/images/generations") || base.endsWith("/images/edits")) {
            int idx = base.lastIndexOf("/images/");
            base = base.substring(0, idx);
        }
        return base;
    }

    public static String imagesGenerations(String v1Base) {
        return stripTrailingSlash(v1Base) + "/images/generations";
    }

    public static String imagesEdits(String v1Base) {
        return stripTrailingSlash(v1Base) + "/images/edits";
    }

    public static java.net.URI toUri(String url) {
        return java.net.URI.create(url);
    }

    private static String stripTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    static boolean looksOfficial(String provider) {
        String prov = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        return prov.contains("官方") || prov.contains("openai") || prov.contains("open ai");
    }
}
