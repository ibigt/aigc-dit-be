package cn.aigc.dit.generate.ark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 素材库错误原文：优先 {@code ResponseMetadata.Error}，其次视频接口那套 {@code error.message}。
 */
final class ArkAssetErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArkAssetErrors() {
    }

    static String fromBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode meta = root.path("ResponseMetadata").path("Error");
            String fromMeta = original(text(meta, "Code"), text(meta, "Message"));
            if (fromMeta != null) {
                return fromMeta;
            }
            return ArkErrors.message(body);
        } catch (Exception e) {
            return null;
        }
    }

    static String original(String code, String message) {
        String normalizedCode = code == null ? "" : code.trim();
        String normalizedMessage = message == null ? "" : message.trim();
        if (normalizedCode.isEmpty()) {
            return normalizedMessage.isEmpty() ? null : normalizedMessage;
        }
        if (normalizedMessage.isEmpty() || normalizedMessage.contains(normalizedCode)) {
            return normalizedMessage.isEmpty() ? normalizedCode : normalizedMessage;
        }
        return normalizedCode + ": " + normalizedMessage;
    }

    static boolean missingAsset(String body) {
        String text = fromBody(body);
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("notfound")
                || lower.contains("not found")
                || text.contains("不存在");
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("");
        return value.isBlank() ? null : value.trim();
    }
}
