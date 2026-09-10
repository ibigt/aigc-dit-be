package cn.aigc.dit.generate.agnes;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

/**
 * 解析 Agnes 视频创建/查询响应：查询必须用 {@code video_id}，成片地址在 {@code metadata.url}。
 */
final class AgnesResponses {

    private AgnesResponses() {
    }

    static String videoId(JsonNode root) {
        String value = firstNonBlank(
                text(root, "video_id"),
                text(root, "videoId"),
                text(root.path("data"), "video_id"),
                text(root.path("data"), "videoId"),
                text(root.path("video"), "video_id"));
        if (StringUtils.hasText(value)) {
            return value;
        }
        String nested = firstNonBlank(
                text(root.path("video"), "video_id"),
                text(root.path("video"), "id"));
        if (looksLikeVideoId(nested)) {
            return nested;
        }
        String id = firstNonBlank(text(root, "id"), text(root.path("data"), "id"));
        return looksLikeVideoId(id) ? id : "";
    }

    private static boolean looksLikeVideoId(String value) {
        return StringUtils.hasText(value) && value.toLowerCase().startsWith("video_");
    }

    static String taskId(JsonNode root) {
        return firstNonBlank(
                text(root, "task_id"),
                text(root, "taskId"),
                text(root, "id"),
                text(root.path("data"), "id"));
    }

    /**
     * 查询用 ID：优先 {@code video_id}。没有时才退回 {@code id}（官方 SDK 也如此）。
     */
    static String pollId(JsonNode root) {
        return firstNonBlank(videoId(root), taskId(root));
    }

    static String outputUrl(JsonNode root) {
        return firstNonBlank(
                text(root.path("metadata"), "url"),
                text(root.path("metadata"), "video_url"),
                text(root, "url"),
                text(root, "video_url"),
                text(root.path("data"), "url"),
                text(root.path("data").path("metadata"), "url"));
    }

    static String status(JsonNode root) {
        return firstNonBlank(text(root, "status"), text(root.path("data"), "status"));
    }

    static int progress(JsonNode root, int fallback) {
        JsonNode node = root.path("progress");
        if (node.isMissingNode() || node.isNull()) {
            node = root.path("data").path("progress");
        }
        if (node.isNumber()) {
            return node.asInt(fallback);
        }
        return fallback;
    }

    static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        String value = node.get(field).asText("");
        return value == null ? "" : value.trim();
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
