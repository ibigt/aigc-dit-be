package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
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
    void pingPostsMinimalChatBody() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<URI> uri = new AtomicReference<>();
        AtomicReference<Duration> timeout = new AtomicReference<>();
        OpenAiCompatChatClient client = new OpenAiCompatChatClient(new ObjectMapper(),
                (target, apiKey, json, wait) -> {
                    uri.set(target);
                    body.set(json);
                    timeout.set(wait);
                    assertEquals("sk-test", apiKey);
                    return new OpenAiCompatChatClient.Transport.Response(200, "{\"id\":\"ok\"}");
                });
        client.ping("https://api.agnes-ai.cn/v1", "sk-test", "agnes-2.5-flash");
        assertEquals("https://api.agnes-ai.cn/v1/chat/completions", uri.get().toString());
        assertEquals(OpenAiCompatChatClient.PING_TIMEOUT, timeout.get());
        assertTrue(body.get().contains("\"content\":\"ping\""));
        assertTrue(body.get().contains("\"max_tokens\":1"));
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
