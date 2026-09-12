package cn.aigc.dit.generate.ark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * 解析方舟错误体：{@code error.message} 或顶层 {@code message}。
 */
final class ArkErrors {

    static final String REAL_PERSON_MESSAGE =
            "参考图可能包含真人，官方 Seedance 已拒绝。请更换不含真人脸的参考图，或改用 MiniMax 等本地模型。";

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
                return friendly(error.asText());
            }
            String nested = text(error, "message");
            if (nested != null) {
                return friendly(nested);
            }
            String code = text(error, "code");
            String message = text(root, "message");
            if (code != null && message != null) {
                return friendly(code + ": " + message);
            }
            return friendly(message != null ? message : code);
        } catch (Exception e) {
            return null;
        }
    }

    static String friendly(String detail) {
        if (detail == null || detail.isBlank()) {
            return detail;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("real person") || detail.contains("真人")) {
            return REAL_PERSON_MESSAGE;
        }
        return detail;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("");
        return value.isBlank() ? null : value.trim();
    }
}
