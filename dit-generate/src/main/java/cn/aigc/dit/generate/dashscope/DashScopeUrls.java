package cn.aigc.dit.generate.dashscope;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * DashScope 异步视频接口：创建 {@code POST /services/aigc/video-generation/video-synthesis}，
 * 查询/取消 {@code /tasks/{task_id}}。
 */
public final class DashScopeUrls {

    public static final String DEFAULT_BASE = "https://dashscope.aliyuncs.com/api/v1";

    private DashScopeUrls() {
    }

    public static String apiBase(String endpoint, String fallback) {
        String raw = blankTo(endpoint, blankTo(fallback, DEFAULT_BASE));
        String base = stripTrailingSlash(raw);
        if (!base.contains("/api/")) {
            base += "/api/v1";
        }
        return stripTrailingSlash(base);
    }

    public static String createTask(String apiBase) {
        return apiBase + "/services/aigc/video-generation/video-synthesis";
    }

    public static String queryTask(String apiBase, String taskId) {
        return apiBase + "/tasks/" + encode(taskId);
    }

    public static String cancelTask(String apiBase, String taskId) {
        return queryTask(apiBase, taskId) + "/cancel";
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
}
