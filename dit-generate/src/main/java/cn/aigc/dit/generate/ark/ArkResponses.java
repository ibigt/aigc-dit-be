package cn.aigc.dit.generate.ark;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

/**
 * 解析方舟创建/查询响应：任务 ID 在 {@code id}，成片在 {@code content.video_url}。
 */
final class ArkResponses {

    private ArkResponses() {
    }

    static String taskId(JsonNode root) {
        return firstNonBlank(
                text(root, "id"),
                text(root, "task_id"),
                text(root.path("data"), "id"));
    }

    static String status(JsonNode root) {
        return firstNonBlank(text(root, "status"), text(root.path("data"), "status")).toLowerCase();
    }

    static String outputUrl(JsonNode root) {
        return firstNonBlank(
                text(root.path("content"), "video_url"),
                text(root.path("content"), "url"),
                text(root.path("data").path("content"), "video_url"),
                text(root, "video_url"));
    }

    static String errorMessage(JsonNode root) {
        JsonNode error = root.path("error");
        if (error.isTextual()) {
            return error.asText();
        }
        return firstNonBlank(text(error, "message"), text(root, "message"));
    }

    static boolean succeeded(String status) {
        return "succeeded".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status);
    }

    static boolean failed(String status) {
        return "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)
                || "canceled".equalsIgnoreCase(status)
                || "expired".equalsIgnoreCase(status);
    }

    static boolean queued(String status) {
        return "queued".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status);
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
