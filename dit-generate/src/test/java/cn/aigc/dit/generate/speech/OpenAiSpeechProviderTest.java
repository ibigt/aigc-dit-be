package cn.aigc.dit.generate.speech;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
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

class OpenAiSpeechProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void submitMapsSpeechRequestAndStoresAudio() throws Exception {
        RecordingHttp http = new RecordingHttp(new byte[]{1, 2, 3, 4});
        GenerateProperties properties = new GenerateProperties();
        properties.getSpeech().setBaseUrl("https://fallback.example/v1");
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(
                new LocalObjectStorage(tempDir), properties, http, new ObjectMapper());
        GenerateRequest request = new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("audio")
                .setMode("t2a")
                .setEndpoint("https://speech.example/v1/")
                .setApiKey("secret")
                .setModelKey("tts-pro")
                .setPrompt("  欢迎使用语音创作  ")
                .setVoiceId("nova")
                .setSpeed(1.25)
                .setAudioFormat("wav")
                .setAdapterConfig(Map.of("instructions", "语气温暖"));

        SubmitResult submitted = provider.submit(request);
        PollResult result = provider.poll(submitted.getJobId());

        JsonNode body = new ObjectMapper().readTree(http.json);
        assertEquals(URI.create("https://speech.example/v1/audio/speech"), http.uri);
        assertEquals("secret", http.apiKey);
        assertEquals("tts-pro", body.path("model").asText());
        assertEquals("欢迎使用语音创作", body.path("input").asText());
        assertEquals("nova", body.path("voice").asText());
        assertEquals("wav", body.path("response_format").asText());
        assertEquals(1.25, body.path("speed").asDouble());
        assertEquals("语气温暖", body.path("instructions").asText());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertEquals("users/8/outputs/42/1.wav", result.getOutputStorageKey());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(tempDir.resolve(result.getOutputStorageKey())));
    }

    @Test
    void rememberRestoresDeterministicOutputAfterRestart() {
        GenerateProperties properties = new GenerateProperties();
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(
                new LocalObjectStorage(tempDir), properties, new RecordingHttp(new byte[]{1}), new ObjectMapper());
        GenerateRequest request = new GenerateRequest()
                .setUserId(3L)
                .setTaskId(9L)
                .setCategory("audio")
                .setMode("t2a")
                .setPrompt("恢复")
                .setAudioFormat("mp3");

        provider.remember("speech-existing", request);

        PollResult result = provider.poll("speech-existing");
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputStorageKey().endsWith("/1.mp3"));
    }

    private static final class RecordingHttp implements SpeechHttp {
        private byte[] response;
        private String getResponse;
        private cn.aigc.dit.common.exception.BizException getError;
        private URI uri;
        private String apiKey;
        private String json;
        private boolean lastWasGet;

        private RecordingHttp(byte[] response) {
            this.response = response;
        }

        @Override
        public byte[] post(URI uri, String apiKey, String json) {
            this.uri = uri;
            this.apiKey = apiKey;
            this.json = json;
            return response;
        }

        @Override
        public String getString(URI uri, String apiKey) {
            this.uri = uri;
            this.apiKey = apiKey;
            this.lastWasGet = true;
            if (getError != null) {
                throw getError;
            }
            return getResponse == null ? "{\"data\":[{\"id\":\"tts-pro\"}]}" : getResponse;
        }
    }

    @Test
    void pingUsesModelsEndpointAndAcceptsListedModel() {
        RecordingHttp http = new RecordingHttp(new byte[]{1});
        http.getResponse = "{\"data\":[{\"id\":\"tts-pro\"}]}";
        GenerateProperties properties = new GenerateProperties();
        properties.getSpeech().setApiKey("test-key");
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(
                new LocalObjectStorage(tempDir), properties, http, new ObjectMapper());
        provider.ping(new GenerateRequest()
                .setCategory("audio")
                .setProvider("自定义配置")
                .setEndpoint("https://speech.example/v1")
                .setApiKey("sk-ok")
                .setModelKey("tts-pro"));
        assertTrue(http.lastWasGet);
        assertEquals(URI.create("https://speech.example/v1/models"), http.uri);
    }

    @Test
    void pingRejectsModelNotInList() {
        RecordingHttp http = new RecordingHttp(new byte[]{1});
        http.getResponse = "{\"data\":[{\"id\":\"other-model\"}]}";
        GenerateProperties properties = new GenerateProperties();
        properties.getSpeech().setApiKey("test-key");
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(
                new LocalObjectStorage(tempDir), properties, http, new ObjectMapper());
        cn.aigc.dit.common.exception.BizException error = assertThrows(
                cn.aigc.dit.common.exception.BizException.class,
                () -> provider.ping(new GenerateRequest()
                        .setCategory("audio")
                        .setProvider("自定义配置")
                        .setEndpoint("https://speech.example/v1")
                        .setApiKey("sk-ok")
                        .setModelKey("tts-pro")));
        assertTrue(error.getMessage().contains("不在服务端模型列表中"));
    }

    @Test
    void pingFailsWhenUnauthorized() {
        RecordingHttp http = new RecordingHttp(new byte[]{1});
        http.getError = new cn.aigc.dit.common.exception.BizException(
                cn.aigc.dit.common.exception.ErrorCode.INTERNAL,
                "语音模型 HTTP 401: invalid api key");
        GenerateProperties properties = new GenerateProperties();
        properties.getSpeech().setApiKey("test-key");
        OpenAiSpeechProvider provider = new OpenAiSpeechProvider(
                new LocalObjectStorage(tempDir), properties, http, new ObjectMapper());
        cn.aigc.dit.common.exception.BizException error = assertThrows(
                cn.aigc.dit.common.exception.BizException.class,
                () -> provider.ping(new GenerateRequest()
                        .setCategory("audio")
                        .setProvider("自定义配置")
                        .setEndpoint("https://speech.example/v1")
                        .setApiKey("sk-bad")
                        .setModelKey("tts-pro")));
        assertTrue(error.getMessage().contains("HTTP 401"));
    }
}
