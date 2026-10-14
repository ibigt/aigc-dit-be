package cn.aigc.dit.generate.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class DashScopeErrors {

    private DashScopeErrors() {
    }

    public static String message(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            String message = firstNonBlank(
                    root.path("message").asText(""),
                    root.path("output").path("message").asText(""));
            if (!message.isBlank()) {
                String code = firstNonBlank(
                        root.path("code").asText(""),
                        root.path("output").path("code").asText(""));
                return code.isBlank() ? message : code + ": " + message;
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
