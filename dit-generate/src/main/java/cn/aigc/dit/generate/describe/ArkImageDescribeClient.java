package cn.aigc.dit.generate.describe;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.ark.ArkKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * 方舟 Responses API 图生文。
 */
@Slf4j
public class ArkImageDescribeClient implements ImageDescribeClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_VISION = "doubao-seed-2-1-pro-260915";

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final String defaultVisionModel;

    public ArkImageDescribeClient(GenerateProperties.Ark ark) {
        this.defaultVisionModel = StringUtils.hasText(ark.getVisionModel())
                ? ark.getVisionModel().trim()
                : DEFAULT_VISION;
        this.requestTimeout = Duration.ofSeconds(Math.max(60, ark.getRequestTimeoutSeconds()));
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.max(3, ark.getConnectTimeoutSeconds())))
                .build();
    }

    ArkImageDescribeClient(HttpClient httpClient, Duration requestTimeout, String defaultVisionModel) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.defaultVisionModel = defaultVisionModel == null || defaultVisionModel.isBlank()
                ? DEFAULT_VISION
                : defaultVisionModel;
    }

    @Override
    public ImageDescribeResult describe(
            byte[] imageBytes,
            String mimeType,
            String userText,
            String endpoint,
            String apiKey,
            String visionModel) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考图为空，无法图生文");
        }
        String key = ArkKeys.normalize(apiKey);
        if (!StringUtils.hasText(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请在管理端填写火山方舟 API Key");
        }
        String model = StringUtils.hasText(visionModel) ? visionModel.trim() : defaultVisionModel;
        String mime = normalizeMime(mimeType);
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        ObjectNode message = input.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "input_image");
        imagePart.put("image_url", dataUrl);
        ObjectNode textPart = content.addObject();
        textPart.put("type", "input_text");
        textPart.put("text", userText == null ? ImageDescribePrompts.CHARACTER_FULL : userText);

        URI uri = URI.create(responsesUrl(endpoint));
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "图生文请求序列化失败");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        String responseBody = send(builder.build());
        String text = extractText(responseBody);
        if (!StringUtils.hasText(text)) {
            throw new BizException(ErrorCode.INTERNAL, "图生文未返回有效文本");
        }
        return CaptionSections.parse(text);
    }

    public String defaultVisionModel() {
        return defaultVisionModel;
    }

    static String responsesUrl(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.isEmpty()) {
            base = "https://ark.cn-beijing.volces.com/api/v3";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/responses")) {
            return base;
        }
        return base + "/responses";
    }

    static String normalizeMime(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return "image/jpeg";
        }
        String mime = mimeType.trim().toLowerCase(Locale.ROOT);
        int semi = mime.indexOf(';');
        if (semi > 0) {
            mime = mime.substring(0, semi).trim();
        }
        return mime.startsWith("image/") ? mime : "image/jpeg";
    }

    static String extractText(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            if (root.has("error") && !root.get("error").isNull()) {
                String msg = root.path("error").path("message").asText("");
                if (!StringUtils.hasText(msg)) {
                    msg = root.path("error").asText(root.path("error").toString());
                }
                throw new BizException(ErrorCode.BAD_REQUEST, "图生文失败: " + msg);
            }
            StringBuilder chunks = new StringBuilder();
            JsonNode output = root.get("output");
            if (output != null && output.isArray()) {
                for (JsonNode item : output) {
                    JsonNode content = item.get("content");
                    if (content != null && content.isArray()) {
                        for (JsonNode c : content) {
                            String type = c.path("type").asText("");
                            if ("output_text".equals(type) || "text".equals(type)) {
                                String t = c.path("text").asText("");
                                if (StringUtils.hasText(t)) {
                                    if (chunks.length() > 0) {
                                        chunks.append('\n');
                                    }
                                    chunks.append(t);
                                }
                            } else if (c.path("output_text").isTextual()) {
                                if (chunks.length() > 0) {
                                    chunks.append('\n');
                                }
                                chunks.append(c.path("output_text").asText());
                            }
                        }
                    }
                    if ("message".equals(item.path("type").asText("")) && item.path("text").isTextual()) {
                        if (chunks.length() > 0) {
                            chunks.append('\n');
                        }
                        chunks.append(item.path("text").asText());
                    }
                }
            }
            if (chunks.length() > 0) {
                return chunks.toString().trim();
            }
            for (String key : new String[]{"output_text", "text", "result"}) {
                if (root.path(key).isTextual() && StringUtils.hasText(root.path(key).asText())) {
                    return root.path(key).asText().trim();
                }
            }
            return "";
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "图生文响应解析失败");
        }
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status == 401) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "图生文 HTTP 401: 方舟拒绝了 API Key。官方API 走 /api/v3，token plan 走 /api/plan/v3。");
            }
            if (status >= 400) {
                String detail = body.length() > 400 ? body.substring(0, 400) : body;
                throw new BizException(
                        status >= 500 ? ErrorCode.INTERNAL : ErrorCode.BAD_REQUEST,
                        "图生文 HTTP " + status + (detail.isBlank() ? "" : ": " + detail));
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.INTERNAL, "图生文请求超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "图生文请求被中断");
        } catch (IOException e) {
            log.warn("image describe failed: {}", e.toString());
            throw new BizException(ErrorCode.INTERNAL, "图生文请求失败");
        }
    }
}
