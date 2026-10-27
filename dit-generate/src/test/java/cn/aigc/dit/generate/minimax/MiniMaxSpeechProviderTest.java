package cn.aigc.dit.generate.minimax;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMaxSpeechProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsT2aV2AndDecodesHexAudio() throws Exception {
        RecordingHttp http = new RecordingHttp("{\"data\":{\"audio\":\"0102ff\"},\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}");
        GenerateProperties properties = new GenerateProperties();
        MiniMaxSpeechProvider provider = new MiniMaxSpeechProvider(
                new LocalObjectStorage(tempDir), properties, http, new ObjectMapper());
        GenerateRequest request = new GenerateRequest()
                .setUserId(5L).setTaskId(12L).setCategory("audio").setMode("t2a")
                .setEndpoint("https://api.minimax.cn/v1/t2a_v2")
                .setApiKey("secret").setModelKey("speech-2.8-hd")
                .setPrompt("欢迎使用").setVoiceId("male-qn-qingse")
                .setSpeed(1.15).setAudioFormat("mp3")
                .setEmotion("happy").setCopies(3)
                .setAdapterConfig(Map.of("volume", 1.2, "pitch", 1, "sampleRate", 32000,
                        "bitrate", 128000, "channel", 1, "languageBoost", "Chinese"));

        PollResult result = provider.poll(provider.submit(request).getJobId());

        JsonNode body = new ObjectMapper().readTree(http.json);
        assertEquals(URI.create("https://api.minimax.cn/v1/t2a_v2"), http.uri);
        assertEquals("secret", http.token);
        assertEquals("欢迎使用", body.path("text").asText());
        assertEquals("speech-2.8-hd", body.path("model").asText());
        assertEquals("male-qn-qingse", body.path("voice_setting").path("voice_id").asText());
        assertEquals(1.15, body.path("voice_setting").path("speed").asDouble());
        assertEquals("happy", body.path("voice_setting").path("emotion").asText());
        assertEquals(java.util.List.of("users/5/outputs/12/1.mp3", "users/5/outputs/12/2.mp3", "users/5/outputs/12/3.mp3"), result.getOutputKeys());
        assertEquals("mp3", body.path("audio_setting").path("format").asText());
        assertEquals("Chinese", body.path("language_boost").asText());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertArrayEquals(new byte[]{1, 2, (byte) 255}, Files.readAllBytes(tempDir.resolve(result.getOutputStorageKey())));
    }

    @Test
    void rejectsMusicRatherThanSynthesizingSpeech() {
        var provider = new MiniMaxSpeechProvider(new LocalObjectStorage(tempDir), new GenerateProperties(), new RecordingHttp("{\"data\":{\"audio\":\"0102\"}}"), new ObjectMapper());
        assertThrows(BizException.class, () -> provider.submit(new GenerateRequest().setCategory("audio").setMode("t2a").setPrompt("strings").setAudioMode("music")));
    }

    @Test
    void appendsT2aPathToRootAndSurfacesBusinessError() {
        assertEquals(URI.create("https://api.minimax.cn/v1/t2a_v2"),
                MiniMaxSpeechProvider.endpoint("https://api.minimax.cn", null));
        JsonNode error = new ObjectMapper().createObjectNode().set("base_resp",
                new ObjectMapper().createObjectNode().put("status_code", 1002).put("status_msg", "invalid api key"));
        BizException exception = assertThrows(BizException.class, () -> MiniMaxSpeechProvider.assertSuccess(error));
        assertEquals(true, exception.getMessage().contains("invalid api key"));
    }

    @Test
    void pingSendsEmptyTextAndIgnoresBusinessError() throws Exception {
        RecordingHttp http = new RecordingHttp(
                "{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"text is empty\"}}");
        GenerateProperties properties = new GenerateProperties();
        properties.getMinimax().setApiKey("test-key");
        MiniMaxSpeechProvider provider = new MiniMaxSpeechProvider(
                new LocalObjectStorage(tempDir), properties, http, new ObjectMapper());
        provider.ping(new GenerateRequest()
                .setCategory("audio")
                .setProvider("官方API")
                .setEndpoint("https://api.minimax.cn")
                .setModelKey("speech-2.8-hd")
                .setApiKey("sk-mm"));
        assertEquals("\"\"", new ObjectMapper().readTree(http.json).path("text").toString());
        assertTrue(http.uri.toString().endsWith("/v1/t2a_v2"));
    }

    @Test
    void pingFailsWhenAuthRejected() {
        RecordingHttp http = new RecordingHttp(
                "{\"base_resp\":{\"status_code\":1002,\"status_msg\":\"invalid api key\"}}");
        GenerateProperties properties = new GenerateProperties();
        properties.getMinimax().setApiKey("test-key");
        MiniMaxSpeechProvider provider = new MiniMaxSpeechProvider(
                new LocalObjectStorage(tempDir), properties, http, new ObjectMapper());
        BizException error = assertThrows(BizException.class, () -> provider.ping(new GenerateRequest()
                .setCategory("audio")
                .setProvider("官方API")
                .setEndpoint("https://api.minimax.cn")
                .setModelKey("speech-2.8-hd")
                .setApiKey("sk-bad")));
        assertTrue(error.getMessage().contains("鉴权失败"));
    }

    private static final class RecordingHttp implements MiniMaxHttp {
        private String response;
        private URI uri;
        private String token;
        private String json;

        private RecordingHttp(String response) { this.response = response; }

        @Override public String postJson(URI uri, String bearerToken, String jsonBody) {
            this.uri = uri; this.token = bearerToken; this.json = jsonBody; return response;
        }
        @Override public String getJson(URI uri, String bearerToken) { throw new UnsupportedOperationException(); }
        @Override public byte[] getBytes(URI uri) { throw new UnsupportedOperationException(); }
    }
}
