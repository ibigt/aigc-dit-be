package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI 兼容 {@code /chat/completions}。管理端测连接与 Gateway 共用。
 */
@Slf4j
@Component
public class OpenAiCompatChatClient {

    static final Duration PING_TIMEOUT = Duration.ofSeconds(10);
    /** 结构化剧本请求包含完整剧本和严格 JSON 输出，复杂剧本允许模型最多运行 10 分钟。 */
    static final Duration CHAT_TIMEOUT = Duration.ofSeconds(600);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; aigc-dit/1.0; +https://www.agnes-ai.cn)";

    private final ObjectMapper objectMapper;
    private final Transport transport;

    /** 两个构造器并存时必须标注入入口，否则 Spring 会去找无参构造导致启动失败。 */
    @Autowired
    public OpenAiCompatChatClient(ObjectMapper objectMapper) {
        this(objectMapper, new JdkTransport());
    }

    OpenAiCompatChatClient(ObjectMapper objectMapper, Transport transport) {
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    public void ping(String endpoint, String apiKey, String model) {
        String body = getModels(endpoint, apiKey, PING_TIMEOUT);
        java.util.List<String> ids = cn.aigc.dit.generate.PingSupport.parseModelIds(body);
        if (ids != null) {
            cn.aigc.dit.generate.PingSupport.assertModelListed(ids, model);
        }
    }

    private String getModels(String endpoint, String apiKey, Duration timeout) {
        URI uri = URI.create(normalizeEndpoint(endpoint) + "/models");
        Transport.Response response;
        try {
            response = transport.get(uri, apiKey == null ? "" : apiKey.trim(), timeout);
        } catch (BizException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "连接超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "测试连接被中断");
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BizException(ErrorCode.BAD_REQUEST, "连接失败: " + truncate(detail, 240));
        }
        int status = response.status();
        String raw = response.body() == null ? "" : response.body();
        if (status == 405 || status == 403) {
            return "";
        }
        if (status < 200 || status >= 300) {
            throw new BizException(ErrorCode.BAD_REQUEST, formatError(status, raw));
        }
        return raw;
    }

    public TextChatResult chat(String endpoint, String apiKey, String model, TextChatRequest req) {
        return chat(endpoint, apiKey, model, req, Map.of());
    }

    public TextChatResult chat(String endpoint, String apiKey, String model, TextChatRequest req,
                               Map<String, Object> adapterConfig) {
        if (req == null || !StringUtils.hasText(req.getUser())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "对话内容不能为空");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        boolean supportsSystemRole = booleanOption(adapterConfig, "supportsSystemRole", true);
        if (supportsSystemRole && StringUtils.hasText(req.getSystem())) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", req.getSystem());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        String userContent = !supportsSystemRole && StringUtils.hasText(req.getSystem())
                ? req.getSystem().trim() + "\n\n" + req.getUser()
                : req.getUser();
        user.put("content", userContent);
        if (req.jsonMode()) {
            String structuredOutput = option(adapterConfig, "structuredOutput", "json_object");
            if (!"plain_json".equalsIgnoreCase(structuredOutput)) {
                body.putObject("response_format").put("type", "json_object");
            }
            String tokenParameter = option(adapterConfig, "tokenParameter", "max_tokens");
            if (!"max_completion_tokens".equals(tokenParameter)) {
                tokenParameter = "max_tokens";
            }
            body.put(tokenParameter, req.getMaxTokens() == null ? 32768 : req.getMaxTokens());
            // 全部模型一律关闭 vLLM chat_template 思考：32k JSON 输出再叠加 reasoning
            // 会把耗时顶到分钟级，触发客户端超时且无 reasoning 价值。OpenAI 兼容的
            // 非 vLLM 后端收到未知字段会忽略，不会报错。
            body.putObject("chat_template_kwargs").put("enable_thinking", false);
            Double temperature = numberOption(adapterConfig, "temperature");
            if (temperature != null && temperature >= 0 && temperature <= 2) {
                body.put("temperature", temperature);
            }
        }
        JsonNode root = post(endpoint, apiKey, body, CHAT_TIMEOUT, false);
        JsonNode choice = root.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        if (!StringUtils.hasText(content)) {
            content = choice.path("text").asText("");
        }
        JsonNode usage = root.path("usage");
        long promptTokens = tokenCount(usage, "prompt_tokens", "input_tokens");
        long completionTokens = tokenCount(usage, "completion_tokens", "output_tokens");
        long cachedTokens = tokenCount(usage.path("prompt_tokens_details"), "cached_tokens");
        if (cachedTokens == 0L) {
            cachedTokens = tokenCount(usage.path("input_tokens_details"), "cached_tokens");
        }
        return TextChatResult.of(content, root.path("model").asText(model),
                choice.path("finish_reason").asText(null), promptTokens, completionTokens, cachedTokens);
    }

    private static long tokenCount(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.canConvertToLong()) return Math.max(0L, value.asLong());
        }
        return 0L;
    }

    private static String option(Map<String, Object> config, String key, String fallback) {
        if (config == null || config.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(config.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static boolean booleanOption(Map<String, Object> config, String key, boolean fallback) {
        if (config == null || config.get(key) == null) {
            return fallback;
        }
        Object value = config.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean hasOption(Map<String, Object> config, String key) {
        return config != null && config.containsKey(key) && config.get(key) != null;
    }

    private static Double numberOption(Map<String, Object> config, String key) {
        if (!hasOption(config, key)) {
            return null;
        }
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private JsonNode post(String endpoint, String apiKey, ObjectNode body, Duration timeout, boolean ping) {
        if (!StringUtils.hasText(endpoint)) {
            throw new BizException(ErrorCode.BAD_REQUEST, ping ? "请填写接口地址" : "文本模型未配置地址");
        }
        if (!StringUtils.hasText(body.path("model").asText())) {
            throw new BizException(ErrorCode.BAD_REQUEST, ping ? "请填写模型标识" : "文本模型标识不能为空");
        }
        URI uri = URI.create(chatCompletionsUrl(endpoint));
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "构造对话请求失败");
        }
        Transport.Response response;
        try {
            response = transport.post(uri, apiKey == null ? "" : apiKey.trim(), json, timeout);
        } catch (BizException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, ping ? "连接超时" : "文本模型请求超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, ping ? "测试连接被中断" : "文本模型请求被中断");
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new BizException(ErrorCode.BAD_REQUEST, (ping ? "连接失败: " : "文本模型请求失败: ") + truncate(detail, 240));
        }
        int status = response.status();
        String raw = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            throw new BizException(ErrorCode.BAD_REQUEST, formatError(status, raw));
        }
        if (!StringUtils.hasText(raw)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            if (ping) {
                return objectMapper.createObjectNode();
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "文本模型返回无法解析: " + truncate(raw, 240));
        }
    }

    static String chatCompletionsUrl(String endpoint) {
        return normalizeEndpoint(endpoint) + "/chat/completions";
    }

    /**
     * 去掉末尾斜杠；已含 {@code /v1} 则截到该层，否则补 {@code /v1}。
     */
    public static String normalizeEndpoint(String endpoint) {
        String raw = stripTrailingSlash(endpoint == null ? "" : endpoint.trim());
        if (raw.isEmpty()) {
            return raw;
        }
        int marker = raw.toLowerCase().indexOf("/v1");
        if (marker >= 0) {
            return raw.substring(0, marker + 3);
        }
        return raw + "/v1";
    }

    static String formatError(int status, String body) {
        String detail = extractMessage(body);
        if (detail == null || detail.isBlank()) {
            detail = body == null ? "" : body.trim();
        }
        String prefix = "连接失败 HTTP " + status;
        if (detail.isBlank()) {
            return prefix;
        }
        return prefix + ": " + truncate(detail.replaceAll("\\s+", " "), 240);
    }

    static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode error = root.path("error");
            if (error.isTextual() && StringUtils.hasText(error.asText())) {
                return error.asText();
            }
            if (error.hasNonNull("message")) {
                return error.path("message").asText();
            }
            if (root.hasNonNull("message")) {
                return root.path("message").asText();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String stripTrailingSlash(String value) {
        String text = value;
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    interface Transport {
        Response post(URI uri, String apiKey, String jsonBody, Duration timeout) throws Exception;

        /**
         * 默认转给 {@link #post}，便于老测试桩继续编译；{@link JdkTransport} 重写为真 GET。
         */
        default Response get(URI uri, String apiKey, Duration timeout) throws Exception {
            return post(uri, apiKey, "{}", timeout);
        }

        record Response(int status, String body) {
        }
    }

    static final class JdkTransport implements Transport {
        private final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        @Override
        public Response post(URI uri, String apiKey, String jsonBody, Duration timeout) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (StringUtils.hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        }

        @Override
        public Response get(URI uri, String apiKey, Duration timeout) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET();
            if (StringUtils.hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        }
    }
}
