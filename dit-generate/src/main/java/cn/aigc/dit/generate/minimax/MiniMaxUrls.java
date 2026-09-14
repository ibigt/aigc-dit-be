package cn.aigc.dit.generate.minimax;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * MiniMax 视频 V2：创建 {@code POST /v2/video_generation}，查询 {@code GET /v2/query/video_generation/{task_id}}。
 */
public final class MiniMaxUrls {

    public static final String DEFAULT_BASE = "https://api.minimax.cn";

    private MiniMaxUrls() {
    }

    public static String origin(String endpoint, String fallback) {
        String raw = stripTrailingSlash(blankTo(endpoint, blankTo(fallback, DEFAULT_BASE)));
        int marker = indexOfIgnoreCase(raw, "/v2");
        if (marker >= 0) {
            return stripTrailingSlash(raw.substring(0, marker));
        }
        return raw;
    }

    public static String createTask(String origin) {
        return stripTrailingSlash(origin) + "/v2/video_generation";
    }

    public static String queryTask(String origin, String taskId) {
        return stripTrailingSlash(origin) + "/v2/query/video_generation/" + encode(taskId);
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
