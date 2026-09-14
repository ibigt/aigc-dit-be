package cn.aigc.dit.generate.minimax;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.FramePair;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.ReferenceAsset;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMaxGenerateProviderTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    private FakeHttp http;
    private MiniMaxGenerateProvider provider;
    private LocalObjectStorage storage;
    private GenerateProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GenerateProperties();
        properties.getMinimax().setApiKey("test-key");
        properties.getMinimax().setPollIntervalSeconds(0);
        properties.getMinimax().setRateLimitBackoffSeconds(0);
        http = new FakeHttp();
        storage = new LocalObjectStorage(tempDir);
        provider = new MiniMaxGenerateProvider(storage, properties, http, mapper);
    }

    @Test
    void submitT2vThenPollSucceededDownloadsMp4() throws Exception {
        http.postBody = """
                {"task_id":"424010985738629"}
                """;
        http.getBody = """
                {"task":{"id":"424010985738629","status":"succeeded","content":{"url":"https://cdn.example/out.mp4"}}}
                """;
        http.download = new byte[]{1, 2, 3, 4, 5};

        SubmitResult submitted = provider.submit(video("t2v"));
        assertEquals("424010985738629", submitted.getJobId());
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("MiniMax-H3", body.get("model").asText());
        assertEquals("768P", body.get("resolution").asText());
        assertEquals(5, body.get("duration").asInt());
        assertTrue(http.lastUri.toString().endsWith("/v2/video_generation"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
        assertTrue(http.lastPollUri.toString().contains("/v2/query/video_generation/424010985738629"));
        assertEquals(1, http.pollCount);
    }

    @Test
    void submitR2vSendsDataUriReference() throws Exception {
        http.postBody = """
                {"task_id":"r2v-1"}
                """;
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");

        provider.submit(video("r2v").setReferences(List.of(new ReferenceAsset("image", "users/8/assets/a.png"))));
        JsonNode body = mapper.readTree(http.lastJson);
        String url = body.get("content").get(1).get("image_url").get("url").asText();
        assertEquals("reference_image", body.get("content").get(1).get("role").asText());
        assertTrue(url.startsWith("data:image/png;base64,"));
    }

    @Test
    void submitI2vSendsFirstAndLastFrame() throws Exception {
        http.postBody = """
                {"task_id":"i2v-1"}
                """;
        storage.put("users/8/assets/first.png", TINY_PNG, "image/png");
        storage.put("users/8/assets/last.png", TINY_PNG, "image/png");

        provider.submit(video("i2v").setFrames(new FramePair("users/8/assets/first.png", "users/8/assets/last.png")));
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("first_frame", body.get("content").get(1).get("role").asText());
        assertEquals("last_frame", body.get("content").get(2).get("role").asText());
        assertEquals("adaptive", body.get("ratio").asText());
    }

    @Test
    void maxModelRejectsR2v() {
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");
        BizException error = assertThrows(BizException.class, () -> provider.submit(
                video("r2v")
                        .setModelKey("MiniMax-H3-Max")
                        .setReferences(List.of(new ReferenceAsset("image", "users/8/assets/a.png")))));
        assertEquals(ErrorCode.BAD_REQUEST, error.getCode());
        assertTrue(error.getMessage().contains("H3-Max"));
    }

    @Test
    void pollRateLimitSkipsImmediateSecondHttp() {
        properties.getMinimax().setRateLimitBackoffSeconds(60);
        http.postBody = """
                {"task_id":"rl-1"}
                """;
        http.pollError = new BizException(ErrorCode.INTERNAL, "MiniMax 查询 HTTP 429: rate limit");

        SubmitResult submitted = provider.submit(video("t2v"));
        PollResult first = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, first.getStatus());
        assertEquals(1, http.pollCount);

        PollResult second = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, second.getStatus());
        assertEquals(1, http.pollCount);
    }

    private static GenerateRequest video(String mode) {
        return new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("video")
                .setAdapterKey(AdapterCatalog.MINIMAX_H3)
                .setModelKey("MiniMax-H3")
                .setMode(mode)
                .setPrompt("雨后的未来城市街道")
                .setResolution("720P")
                .setRatio("16:9")
                .setDurationSec(5)
                .setEndpoint("https://api.minimax.cn")
                .setApiKey("sk-minimax");
    }

    private static final class FakeHttp implements MiniMaxHttp {
        private String postBody = "{}";
        private String getBody = "{\"task\":{\"status\":\"running\"}}";
        private byte[] download = TINY_PNG;
        private URI lastUri;
        private URI lastPollUri;
        private String lastJson;
        private int pollCount;
        private BizException pollError;

        @Override
        public String postJson(URI uri, String bearerToken, String jsonBody) {
            lastUri = uri;
            lastJson = jsonBody;
            return postBody;
        }

        @Override
        public String getJson(URI uri, String bearerToken) {
            lastPollUri = uri;
            pollCount++;
            if (pollError != null) {
                throw pollError;
            }
            return getBody;
        }

        @Override
        public byte[] getBytes(URI uri) {
            return download;
        }
    }
}
