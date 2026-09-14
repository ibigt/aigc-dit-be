package cn.aigc.dit.generate.minimax;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

/**
 * 解析 MiniMax 创建/查询响应：创建返回 {@code task_id}，查询在 {@code task} 下。
 */
final class MiniMaxResponses {

    private MiniMaxResponses() {
    }

    static String taskId(JsonNode root) {
        JsonNode task = root.path("task");
        return firstNonBlank(
                text(root, "task_id"),
                text(task, "id"),
                text(root, "id"));
    }

    static String status(JsonNode root) {
        JsonNode task = root.path("task");
        return firstNonBlank(text(task, "status"), text(root, "status")).toLowerCase();
    }

    static String outputUrl(JsonNode root) {
        JsonNode task = root.path("task");
        return firstNonBlank(
                text(task.path("content"), "url"),
                text(root.path("content"), "url"),
                text(root.path("content"), "video_url"));
    }

    static String errorMessage(JsonNode root) {
        JsonNode taskError = root.path("task").path("error");
        JsonNode error = root.path("error");
        if (taskError.isTextual()) {
            return taskError.asText();
        }
        if (error.isTextual()) {
            return error.asText();
        }
        return firstNonBlank(text(taskError, "message"), text(error, "message"), text(root, "message"));
    }

    static boolean succeeded(String status) {
        return "succeeded".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status);
    }

    static boolean failed(String status) {
        return "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)
                || "canceled".equalsIgnoreCase(status);
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
