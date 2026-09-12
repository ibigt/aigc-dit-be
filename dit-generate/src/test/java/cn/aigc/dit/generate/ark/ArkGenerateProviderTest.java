package cn.aigc.dit.generate.ark;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.FramePair;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArkGenerateProviderTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    private FakeHttp http;
    private ArkGenerateProvider provider;
    private LocalObjectStorage storage;
    private GenerateProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GenerateProperties();
        properties.getArk().setApiKey("test-key");
        properties.getArk().setPollIntervalSeconds(0);
        properties.getArk().setRateLimitBackoffSeconds(0);
        http = new FakeHttp();
        storage = new LocalObjectStorage(tempDir);
        provider = new ArkGenerateProvider(storage, properties, http, mapper);
    }

    @Test
    void submitT2vThenPollSucceededDownloadsMp4() throws Exception {
        http.postBody = """
                {"id":"cgt-1","status":"queued"}
                """;
        http.getBody = """
                {"id":"cgt-1","status":"succeeded","content":{"video_url":"https://cdn.example/out.mp4"}}
                """;
        http.download = new byte[]{1, 2, 3, 4, 5};

        SubmitResult submitted = provider.submit(video("t2v"));
        assertEquals("cgt-1", submitted.getJobId());
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("doubao-seedance-2-5-260628", body.get("model").asText());
        assertEquals("720p", body.get("resolution").asText());
        assertEquals(5, body.get("duration").asInt());
        assertTrue(http.lastUri.toString().endsWith("/contents/generations/tasks"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
        assertTrue(http.lastPollUri.toString().endsWith("/contents/generations/tasks/cgt-1"));
        assertEquals(1, http.pollCount);
    }

    @Test
    void submitR2vSendsDataUriReference() throws Exception {
        http.postBody = """
                {"id":"cgt-r2v","status":"queued"}
                """;
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");

        provider.submit(video("r2v").setReferences(List.of(new ReferenceAsset("image", "users/8/assets/a.png"))));
        JsonNode body = mapper.readTree(http.lastJson);
        String url = body.get("content").get(1).get("image_url").get("url").asText();
        assertEquals("reference_image", body.get("content").get(1).get("role").asText());
        assertTrue(url.startsWith("data:image/png;base64,"));
    }

    @Test
    void submitR2vSendsReferenceVideo() throws Exception {
        http.postBody = """
                {"id":"cgt-mm","status":"queued"}
                """;
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");
        storage.put("users/8/assets/b.mp4", new byte[]{1, 2, 3, 4}, "video/mp4");

        provider.submit(video("r2v").setReferences(List.of(
                new ReferenceAsset("image", "users/8/assets/a.png"),
                new ReferenceAsset("video", "users/8/assets/b.mp4"))));
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("reference_image", body.get("content").get(1).get("role").asText());
        assertEquals("video_url", body.get("content").get(2).get("type").asText());
        assertEquals("reference_video", body.get("content").get(2).get("role").asText());
        assertEquals(3, body.get("content").size());
    }

    @Test
    void submitI2vSendsFirstAndLastFrame() throws Exception {
        http.postBody = """
                {"id":"cgt-i2v","status":"queued"}
                """;
        storage.put("users/8/assets/first.png", TINY_PNG, "image/png");
        storage.put("users/8/assets/last.png", TINY_PNG, "image/png");

        provider.submit(video("i2v").setFrames(new FramePair("users/8/assets/first.png", "users/8/assets/last.png")));
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("first_frame", body.get("content").get(1).get("role").asText());
        assertEquals("last_frame", body.get("content").get(2).get("role").asText());
    }

    @Test
    void pollRateLimitSkipsImmediateSecondHttp() {
        properties.getArk().setRateLimitBackoffSeconds(60);
        http.postBody = """
                {"id":"cgt-rl","status":"queued"}
                """;
        http.pollError = new BizException(ErrorCode.INTERNAL, "Seedance 查询 HTTP 429: rate limit");

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
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_5)
                .setModelKey("doubao-seedance-2-5-260628")
                .setMode(mode)
                .setPrompt("雨后的未来城市街道")
                .setResolution("720P")
                .setRatio("16:9")
                .setDurationSec(5)
                .setEndpoint("https://ark.cn-beijing.volces.com/api/v3")
                .setApiKey("sk-ark");
    }

    private static final class FakeHttp implements ArkHttp {
        private String postBody = "{}";
        private String getBody = "{\"status\":\"running\"}";
        private byte[] download = TINY_PNG;
        private URI lastUri;
        private URI lastGetUri;
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
            lastGetUri = uri;
            lastPollUri = uri;
            pollCount++;
            if (pollError != null) {
                throw pollError;
            }
            return getBody;
        }

        @Override
        public byte[] getBytes(URI uri) {
            lastGetUri = uri;
            return download;
        }
    }
}
