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
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768;
    private static final int DEFAULT_OUTPUT_TOKENS = 8_192;
    private static final int CONTEXT_SAFETY_TOKENS = 1_024;
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
        // 部分兼容网关（含火山方舟）不实现 GET /models；鉴权失败也可能是 403。
        // 405/404/501/403 视为“列表不可用”，跳过模型存在性校验，由后续真实对话再验。
        if (status == 405 || status == 404 || status == 501 || status == 403) {
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
            int requestedTokens = req.getMaxTokens() == null
                    ? DEFAULT_OUTPUT_TOKENS : Math.max(1, req.getMaxTokens());
            int contextWindowTokens = integerOption(adapterConfig, "contextWindowTokens",
                    DEFAULT_CONTEXT_WINDOW_TOKENS);
            int estimatedInputTokens = estimateInputTokens(req.getSystem(), userContent);
            int availableOutputTokens = Math.max(256,
                    contextWindowTokens - estimatedInputTokens - CONTEXT_SAFETY_TOKENS);
            int outputTokens = Math.min(requestedTokens, availableOutputTokens);
            if (outputTokens < requestedTokens) {
                log.info("[text-chat] cap output tokens requested={} actual={} contextWindow={} estimatedInput={}",
                        requestedTokens, outputTokens, contextWindowTokens, estimatedInputTokens);
            }
            body.put(tokenParameter, outputTokens);
            // 全部模型一律关闭 vLLM chat_template 思考：32k JSON 输出再叠加 reasoning
            // 会把耗时顶到分钟级，触发客户端超时且无 reasoning 价值。OpenAI 兼容的
            // 非 vLLM 后端收到未知字段会忽略，不会报错。
            body.putObject("chat_template_kwargs").put("enable_thinking", false);
            // MiniMax M3 等思考模型不认 vLLM 的 chat_template_kwargs，思考照发且直接
            // 挤占 max_tokens；这类模型在模型配置里指定顶层开关（thinkingField +
            // thinkingType，如 thinking / disabled）才能真正关掉。官方亦不建议
            // thinking 与 response_format 同开，本层全程 JSON mode，正好关死。
            String thinkingField = option(adapterConfig, "thinkingField", "");
            if (!thinkingField.isBlank()) {
                body.putObject(thinkingField).put("type",
                        option(adapterConfig, "thinkingType", "disabled"));
            }
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
        content = stripReasoning(content);
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

    /**
     * 剥掉思考模型混在 content 里的 {@code <think>...</think>} 推理段。
     * <p>
     * MiniMax M 系列等把推理直接写进 content：轻则解析器从推理草稿里的第一个
     * {@code {} 抓错对象，重则思考本身耗尽 max_tokens，正式 JSON 根本没写出来
     * （未闭合的 {@code <think>} 即此情形，返回空串交给上层按截断处理）。
     */
    static String stripReasoning(String content) {
        if (content == null) {
            return null;
        }
        int opened = content.indexOf("<think>");
        if (opened < 0) {
            return content;
        }
        int closed = content.indexOf("</think>");
        if (closed < 0) {
            return content.substring(0, opened);
        }
        return stripReasoning(content.substring(0, opened) + content.substring(closed + "</think>".length()));
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

    private static int integerOption(Map<String, Object> config, String key, int fallback) {
        if (!hasOption(config, key)) {
            return fallback;
        }
        Object value = config.get(key);
        try {
            int parsed = value instanceof Number number
                    ? number.intValue() : Integer.parseInt(String.valueOf(value).trim());
            return parsed > CONTEXT_SAFETY_TOKENS ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * 保守估算输入预算：中文通常接近一字一 token，英文会有更多余量。
     * 精确分词由各供应商决定，此处只负责避免 max_tokens 占满整个上下文窗口。
     */
    private static int estimateInputTokens(String system, String user) {
        int systemChars = system == null ? 0 : system.codePointCount(0, system.length());
        int userChars = user == null ? 0 : user.codePointCount(0, user.length());
        return Math.max(1, systemChars + userChars);
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
     * 去掉末尾斜杠。
     * <ul>
     *   <li>已含 OpenAI 风格 {@code /v1}：截到该层</li>
     *   <li>已是方舟等版本化根路径（{@code /api/v3}、{@code /api/plan/v3} 等）：原样保留，勿再拼 {@code /v1}</li>
     *   <li>否则补 {@code /v1}</li>
     * </ul>
     */
    public static String normalizeEndpoint(String endpoint) {
        String raw = stripTrailingSlash(endpoint == null ? "" : endpoint.trim());
        if (raw.isEmpty()) {
            return raw;
        }
        String lower = raw.toLowerCase();
        int v1 = indexOfPathVersion(lower, "/v1");
        if (v1 >= 0) {
            return raw.substring(0, v1 + 3);
        }
        if (isVersionedApiRoot(lower)) {
            return raw;
        }
        return raw + "/v1";
    }

    /** 匹配 {@code /v1} 作为路径段，避免误伤 {@code /v10}。 */
    private static int indexOfPathVersion(String lower, String marker) {
        int from = 0;
        while (from < lower.length()) {
            int at = lower.indexOf(marker, from);
            if (at < 0) {
                return -1;
            }
            int after = at + marker.length();
            if (after >= lower.length() || lower.charAt(after) == '/' || lower.charAt(after) == '?') {
                return at;
            }
            from = after;
        }
        return -1;
    }

    /** 方舟 OpenAI 兼容根：{@code .../api/v3}、{@code .../api/plan/v3}、{@code .../api/coding/v3}。 */
    private static boolean isVersionedApiRoot(String lower) {
        return lower.matches(".*/api(?:/[a-z][a-z0-9_-]*)?/v\\d+$");
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
