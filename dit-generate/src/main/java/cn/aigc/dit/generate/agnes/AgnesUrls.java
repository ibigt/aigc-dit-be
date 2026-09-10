package cn.aigc.dit.generate.agnes;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Agnes 官方 REST 地址。创建走 {@code /v1/videos} 或 {@code /v1/images/generations}，
 * 视频查询走源站 {@code /agnesapi?video_id=&model_name=}，不在 {@code /v1} 下。
 */
public final class AgnesUrls {

    public static final String DEFAULT_BASE = "https://api.agnes-ai.cn/v1";

    private AgnesUrls() {
    }

    public static String v1Base(String endpoint, String fallback) {
        String raw = stripTrailingSlash(blankTo(endpoint, blankTo(fallback, DEFAULT_BASE)));
        int marker = indexOfIgnoreCase(raw, "/v1");
        if (marker >= 0) {
            return raw.substring(0, marker + 3);
        }
        return raw + "/v1";
    }

    public static String origin(String v1Base) {
        String raw = stripTrailingSlash(blankTo(v1Base, DEFAULT_BASE));
        if (raw.length() >= 3 && raw.substring(raw.length() - 3).equalsIgnoreCase("/v1")) {
            return stripTrailingSlash(raw.substring(0, raw.length() - 3));
        }
        return raw;
    }

    public static String imagesGenerations(String v1Base) {
        return stripTrailingSlash(v1Base) + "/images/generations";
    }

    public static String videos(String v1Base) {
        return stripTrailingSlash(v1Base) + "/videos";
    }

    public static String videoRetrieve(String v1Base, String videoId) {
        return videos(v1Base) + "/" + encode(videoId);
    }

    public static URI poll(String v1Base, String videoId, String modelName) {
        String query = "video_id=" + encode(videoId) + "&model_name=" + encode(modelName);
        return URI.create(origin(v1Base) + "/agnesapi?" + query);
    }

    public static URI toUri(String url) {
        return URI.create(url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(blankTo(value, ""), StandardCharsets.UTF_8);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String text = value;
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
    }
}
