package cn.aigc.dit.generate.minimax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析 MiniMax OpenAI 风格错误体：{@code error.message}。
 */
final class MiniMaxErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MiniMaxErrors() {
    }

    static String message(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.path("error");
            if (error.isTextual()) {
                return error.asText();
            }
            String nested = text(error, "message");
            if (nested != null) {
                return nested;
            }
            JsonNode taskError = root.path("task").path("error");
            String taskMessage = text(taskError, "message");
            if (taskMessage != null) {
                return taskMessage;
            }
            return text(root, "message");
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("");
        return value.isBlank() ? null : value.trim();
    }
}
