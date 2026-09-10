package cn.aigc.dit.generate.agnes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析 Agnes 错误体：{@code detail} 或 OpenAI 风格 {@code error.message}。
 */
final class AgnesErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgnesErrors() {
    }

    static String message(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            String detail = text(root, "detail");
            if (detail != null) {
                return detail;
            }
            JsonNode error = root.path("error");
            if (error.isTextual()) {
                return error.asText();
            }
            String nested = text(error, "message");
            if (nested != null) {
                return nested;
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
