package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatChatClientTest {

    @Test
    void normalizeEndpointDoesNotStackV1() {
        assertEquals("https://api.agnes-ai.cn/v1",
                OpenAiCompatChatClient.normalizeEndpoint("https://api.agnes-ai.cn/v1/"));
        assertEquals("https://api.agnes-ai.cn/v1",
                OpenAiCompatChatClient.normalizeEndpoint("https://api.agnes-ai.cn"));
        assertEquals("https://api.agnes-ai.cn/v1/chat/completions",
                OpenAiCompatChatClient.chatCompletionsUrl("https://api.agnes-ai.cn/v1"));
    }

    @Test
    void normalizeEndpointKeepsArkApiV3Root() {
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
                OpenAiCompatChatClient.normalizeEndpoint("https://ark.cn-beijing.volces.com/api/v3/"));
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3",
                OpenAiCompatChatClient.normalizeEndpoint("https://ark.cn-beijing.volces.com/api/plan/v3"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                OpenAiCompatChatClient.chatCompletionsUrl("https://ark.cn-beijing.volces.com/api/v3"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                OpenAiCompatChatClient.chatCompletionsUrl("https://ark.cn-beijing.volces.com/api/v3/"));
    }

    @Test
    void pingTreatsModels404AsSoftPass() {
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(new ObjectMapper(),
                (target, apiKey, json, wait) -> {
                    assertEquals("https://ark.cn-beijing.volces.com/api/v3/models", target.toString());
                    return new OpenAiCompatChatClient.Transport.Response(404, "{\"error\":{\"message\":\"not found\"}}");
                });
        client.ping("https://ark.cn-beijing.volces.com/api/v3", "sk-test", "deepseek-v4-flash");
    }

    @Test
    void pingPostsMinimalChatBody() {
        AtomicReference<URI> uri = new AtomicReference<>();
        AtomicReference<Duration> timeout = new AtomicReference<>();
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(new ObjectMapper(),
                (target, apiKey, json, wait) -> {
                    uri.set(target);
                    timeout.set(wait);
                    assertEquals("sk-test", apiKey);
                    return new OpenAiCompatChatClient.Transport.Response(200,
                            "{\"data\":[{\"id\":\"agnes-2.5-flash\"}]}");
                });
        client.ping("https://api.agnes-ai.cn/v1", "sk-test", "agnes-2.5-flash");
        assertEquals("https://api.agnes-ai.cn/v1/models", uri.get().toString());
        assertEquals(OpenAiCompatChatClient.PING_TIMEOUT, timeout.get());
    }

    @Test
    void pingTruncatesGatewayError() {
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(new ObjectMapper(),
                (target, apiKey, json, wait) -> new OpenAiCompatChatClient.Transport.Response(
                        401, "{\"error\":{\"message\":\"invalid api key from upstream gateway\"}}"));
        BizException error = assertThrows(BizException.class,
                () -> client.ping("https://api.openai.com/v1", "bad", "gpt-4o-mini"));
        assertTrue(error.getMessage().contains("invalid api key"));
        assertTrue(error.getMessage().contains("401"));
    }

    @Test
    void chatReadsAssistantContent() {
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(new ObjectMapper(),
                (target, apiKey, json, wait) -> new OpenAiCompatChatClient.Transport.Response(
                        200, "{\"model\":\"agnes-2.5-flash\",\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}"));
        TextChatResult result = client.chat("https://api.agnes-ai.cn/v1", "sk", "agnes-2.5-flash",
                new TextChatRequest("sys", "user", false));
        assertEquals("hello", result.getContent());
        assertEquals("agnes-2.5-flash", result.getModel());
        assertEquals("stop", result.getFinishReason());
    }

    @Test
    void chatReadsOpenAiCompatibleTokenUsage() {
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(new ObjectMapper(),
                (target, apiKey, json, wait) -> new OpenAiCompatChatClient.Transport.Response(
                        200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":300,\"prompt_tokens_details\":{\"cached_tokens\":200}}}"));

        TextChatResult result = client.chat("https://example.com/v1", "sk", "portable-model",
                new TextChatRequest("sys", "user", false));

        assertEquals(1200L, result.getPromptTokens());
        assertEquals(300L, result.getCompletionTokens());
        assertEquals(200L, result.getCachedPromptTokens());
    }

    @Test
    void chatReadsResponsesStyleTokenUsageAliases() {
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(new ObjectMapper(),
                (target, apiKey, json, wait) -> new OpenAiCompatChatClient.Transport.Response(
                        200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"input_tokens\":90,\"output_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":30}}}"));

        TextChatResult result = client.chat("https://example.com/v1", "sk", "portable-model",
                new TextChatRequest("sys", "user", false));

        assertEquals(90L, result.getPromptTokens());
        assertEquals(10L, result.getCompletionTokens());
        assertEquals(30L, result.getCachedPromptTokens());
    }

    @Test
    void jsonChatSendsRequestedTokenLimitForNonDeepSeekModels() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(mapper,
                (target, apiKey, json, wait) -> {
                    body.set(json);
                    return new OpenAiCompatChatClient.Transport.Response(
                            200, "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
                });

        client.chat("https://api.agnes-ai.cn/v1", "sk", "agnes-2.5-flash",
                new TextChatRequest("sys", "user", true, 4321));

        JsonNode sent = mapper.readTree(body.get());
        assertEquals(4321, sent.path("max_tokens").asInt(),
                "非 DeepSeek 模型也必须收到调用方要求的输出 token 上限，否则长 JSON 会按供应商默认值截断");
    }

    @Test
    void jsonChatKeepsInputRoomInsideContextWindow() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(mapper,
                (target, apiKey, json, wait) -> {
                    body.set(json);
                    return new OpenAiCompatChatClient.Transport.Response(
                            200, "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
                });

        client.chat("https://example.com/v1", "sk", "32k-model",
                new TextChatRequest("系统提示", "用户输入", true, 32768));

        JsonNode sent = mapper.readTree(body.get());
        assertTrue(sent.path("max_tokens").asInt() < 32768,
                "输出预算必须给系统提示词和用户输入预留上下文空间");
    }

    @Test
    void modelOptionsCanUsePlainJsonAndAlternateTokenParameter() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(mapper,
                (target, apiKey, json, wait) -> {
                    body.set(json);
                    return new OpenAiCompatChatClient.Transport.Response(
                            200, "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
                });

        client.chat("https://example.com/v1", "sk", "portable-model",
                new TextChatRequest("sys", "user", true, 2048),
                Map.of("structuredOutput", "plain_json", "tokenParameter", "max_completion_tokens"));

        JsonNode sent = mapper.readTree(body.get());
        assertTrue(sent.path("response_format").isMissingNode(),
                "不支持 response_format 的模型必须依靠提示词输出 JSON");
        assertEquals(2048, sent.path("max_completion_tokens").asInt());
        assertTrue(sent.path("max_tokens").isMissingNode());
    }

    @Test
    void modelOptionsFoldSystemPromptWhenSystemRoleIsUnsupported() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(mapper,
                (target, apiKey, json, wait) -> {
                    body.set(json);
                    return new OpenAiCompatChatClient.Transport.Response(
                            200, "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
                });

        client.chat("https://example.com/v1", "sk", "no-system-role",
                new TextChatRequest("严格输出 JSON", "解析这份剧本", true, 1024),
                Map.of("supportsSystemRole", false));

        JsonNode messages = mapper.readTree(body.get()).path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.path(0).path("role").asText());
        assertTrue(messages.path(0).path("content").asText().contains("严格输出 JSON"));
        assertTrue(messages.path(0).path("content").asText().contains("解析这份剧本"));
    }

    @Test
    void modelOptionsControlReasoningAndTemperatureWithoutModelNameChecks() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(mapper,
                (target, apiKey, json, wait) -> {
                    body.set(json);
                    return new OpenAiCompatChatClient.Transport.Response(
                            200, "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
                });

        client.chat("https://example.com/v1", "sk", "custom-reasoning-model",
                new TextChatRequest("sys", "user", true, 1024),
                Map.of("enableThinking", false, "temperature", 0.15));

        JsonNode sent = mapper.readTree(body.get());
        assertTrue(sent.path("chat_template_kwargs").has("enable_thinking"));
        assertEquals(false, sent.path("chat_template_kwargs").path("enable_thinking").asBoolean());
        assertEquals(0.15, sent.path("temperature").asDouble(), 0.0001);
    }

    @Test
    void resolvedDefaultReasoningSettingIsSentToJsonRequest() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(mapper,
                (target, apiKey, json, wait) -> {
                    body.set(json);
                    return new OpenAiCompatChatClient.Transport.Response(
                            200, "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}");
                });

        ResolvedTextModel resolved = new ResolvedTextModel(
                11L, "兼容模型", TextAdapterCatalog.OPENAI_COMPAT,
                TextAdapterCatalog.PROVIDER_CUSTOM, "https://example.com/v1", "portable-model");
        client.chat(resolved.endpoint(), "sk", resolved.chatModel(),
                new TextChatRequest("sys", "user", true, 1024), resolved.adapterConfig());

        JsonNode sent = mapper.readTree(body.get());
        assertEquals(false, sent.path("chat_template_kwargs").path("enable_thinking").asBoolean());
    }

    @Test
    void pingAllowsBlankApiKey() {
        AtomicReference<String> sentKey = new AtomicReference<>("unset");
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(new ObjectMapper(),
                (target, apiKey, json, wait) -> {
                    sentKey.set(apiKey);
                    return new OpenAiCompatChatClient.Transport.Response(200, "{\"id\":\"ok\"}");
                });
        client.ping("http://172.171.114.10:8080/v1", null, "deepseek-v3-flash");
        assertEquals("", sentKey.get());
        client.ping("http://172.171.114.10:8080/v1", "  ", "deepseek-v3-flash");
        assertEquals("", sentKey.get());
    }
}
