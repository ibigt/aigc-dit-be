package cn.aigc.dit.generate.dashscope;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

public final class DashScopeResponses {

    private DashScopeResponses() {
    }

    public static String taskId(JsonNode response) {
        return response.path("output").path("task_id").asText("");
    }

    public static String status(JsonNode response) {
        return response.path("output").path("task_status").asText("");
    }

    public static String videoUrl(JsonNode response) {
        JsonNode output = response.path("output");
        String url = output.path("video_url").asText("");
        if (StringUtils.hasText(url)) {
            return url;
        }
        return output.path("video_urls").path(0).path("url").asText("");
    }

    public static String errorMessage(JsonNode response) {
        String message = firstNonBlank(
                response.path("output").path("message").asText(""),
                response.path("message").asText(""));
        if (StringUtils.hasText(message)) {
            return message;
        }
        String code = firstNonBlank(
                response.path("output").path("code").asText(""),
                response.path("code").asText(""));
        return StringUtils.hasText(code) ? code : "";
    }

    public static boolean succeeded(String status) {
        return "SUCCEEDED".equalsIgnoreCase(safe(status));
    }

    public static boolean failed(String status) {
        String value = safe(status);
        return "FAILED".equalsIgnoreCase(value)
                || "CANCELED".equalsIgnoreCase(value)
                || "CANCELLED".equalsIgnoreCase(value);
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
