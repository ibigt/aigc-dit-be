package cn.aigc.dit.generate.ark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析方舟错误体：{@code error.message} 或顶层 {@code message}。
 */
final class ArkErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArkErrors() {
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
            String code = text(error, "code");
            String message = text(root, "message");
            if (code != null && message != null) {
                return code + ": " + message;
            }
            return message != null ? message : code;
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
