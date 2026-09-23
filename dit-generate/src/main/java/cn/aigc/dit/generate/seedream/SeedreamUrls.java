package cn.aigc.dit.generate.seedream;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.ark.ArkUrls;

import java.net.URI;

/**
 * 火山方舟 Seedream 图像：{@code POST /images/generations}。
 * 「官方API」走 {@code /api/v3}，「官方API（token plan）」走 {@code /api/plan/v3}。
 */
public final class SeedreamUrls {

    public static final String IMAGES_PATH = "/images/generations";

    private SeedreamUrls() {
    }

    public static String v3Base(String endpoint, String fallback, String provider) {
        return ArkUrls.v3Base(endpoint, blankTo(fallback, AdapterCatalog.ARK_DEFAULT_ENDPOINT), provider);
    }

    public static String imagesGenerations(String v3Base) {
        return stripTrailingSlash(v3Base) + IMAGES_PATH;
    }

    public static URI toUri(String url) {
        return URI.create(url);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
