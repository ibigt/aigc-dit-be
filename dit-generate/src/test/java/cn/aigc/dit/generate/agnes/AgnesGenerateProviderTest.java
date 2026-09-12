package cn.aigc.dit.generate.agnes;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgnesGenerateProviderTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    private FakeHttp http;
    private AgnesGenerateProvider provider;
    private LocalObjectStorage storage;
    private GenerateProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GenerateProperties();
        properties.getAgnes().setApiKey("test-key");
        properties.getAgnes().setPollIntervalSeconds(0);
        properties.getAgnes().setRateLimitBackoffSeconds(0);
        http = new FakeHttp();
        storage = new LocalObjectStorage(tempDir);
        provider = new AgnesGenerateProvider(storage, properties, http, mapper);
    }

    @Test
    void submitT2iStoresImageFromUrl() throws Exception {
        http.postBody = """
                {"created":1,"data":[{"url":"https://cdn.example/out.png","b64_json":null}]}
                """;
        http.download = TINY_PNG;

        SubmitResult submitted = provider.submit(image("t2i"));
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("agnes-image-2.5-flash", body.get("model").asText());
        assertEquals("1K", body.get("size").asText());
        assertEquals("url", body.get("extra_body").get("response_format").asText());
        assertTrue(http.lastUri.toString().endsWith("/images/generations"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertEquals(1, result.getOutputKeys().size());
        assertTrue(result.getOutputKeys().get(0).endsWith(".png"));
        assertTrue(Files.size(tempDir.resolve(result.getOutputKeys().get(0))) > 10);
    }

    @Test
    void submitI2iSendsDataUriReference() throws Exception {
        http.postBody = """
                {"data":[{"url":"https://cdn.example/out.png"}]}
                """;
        http.download = TINY_PNG;
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");

        provider.submit(image("i2i").setReferences(List.of(new ReferenceAsset("image", "users/8/assets/a.png"))));
        JsonNode body = mapper.readTree(http.lastJson);
        String dataUri = body.get("extra_body").get("image").get(0).asText();
        assertTrue(dataUri.startsWith("data:image/png;base64,"));
    }

    @Test
    void submitT2vThenPollCompletedDownloadsMp4() throws Exception {
        http.postBody = """
                {"id":"task_1","video_id":"video_1","status":"queued"}
                """;
        http.getBody = """
                {"video_id":"video_1","status":"completed","progress":100,"metadata":{"url":"https://cdn.example/out.mp4"}}
                """;
        http.download = new byte[]{1, 2, 3, 4, 5};

        SubmitResult submitted = provider.submit(video("t2v").setDurationSec(5).setRatio("16:9"));
        assertEquals("video_1", submitted.getJobId());
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("text", body.get("mode").asText());
        assertEquals("720P", body.get("size").asText());
        assertEquals("agnes-video-2.5-flash", body.get("model").asText());
        assertTrue(http.lastUri.toString().endsWith("/videos"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
        assertTrue(http.lastPollUri.toString().contains("/agnesapi?video_id=video_1"));
        assertTrue(http.lastPollUri.toString().contains("model_name=agnes-video-2.5-flash"));
        assertEquals(0, http.retrieveCount);
        assertEquals(1, http.pollCount);
    }

    @Test
    void submitResolvesVideoIdFromRetrieveWhenCreateOmitsIt() throws Exception {
        http.postBody = """
                {"id":"task_only","task_id":"task_only","status":"queued"}
                """;
        http.retrieveBody = """
                {"id":"task_only","video_id":"video_resolved","status":"queued"}
                """;
        http.getBody = """
                {"video_id":"video_resolved","status":"completed","metadata":{"url":"https://cdn.example/out.mp4"}}
                """;
        http.download = new byte[]{1, 2, 3, 4, 5};
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");

        SubmitResult submitted = provider.submit(video("r2v").setReferences(
                List.of(new ReferenceAsset("image", "users/8/assets/a.png"))));
        assertEquals("video_resolved", submitted.getJobId());
        assertTrue(http.lastRetrieveUri.toString().endsWith("/videos/task_only"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(http.lastPollUri.toString().contains("video_id=video_resolved"));
        assertEquals(1, http.pollCount);
    }

    @Test
    void pollRateLimitSkipsImmediateSecondHttp() {
        properties.getAgnes().setRateLimitBackoffSeconds(60);
        http.postBody = """
                {"video_id":"video_rl","status":"queued"}
                """;
        http.pollError = new BizException(ErrorCode.INTERNAL,
                "Agnes 查询 HTTP 429: video status query rate limit exceeded");

        SubmitResult submitted = provider.submit(video("t2v"));
        PollResult first = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, first.getStatus());
        assertEquals(1, http.pollCount);

        PollResult second = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, second.getStatus());
        assertEquals(1, http.pollCount);
    }

    @Test
    void completedWithoutUrlStaysRunningThenSucceeds() {
        http.postBody = """
                {"video_id":"video_wait","status":"queued"}
                """;
        http.getBody = """
                {"video_id":"video_wait","status":"completed","progress":100}
                """;
        http.download = new byte[]{1, 2, 3, 4, 5};

        SubmitResult submitted = provider.submit(video("t2v"));
        PollResult waiting = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, waiting.getStatus());
        assertEquals(1, http.pollCount);

        http.getBody = """
                {"video_id":"video_wait","status":"completed","metadata":{"url":"https://cdn.example/out.mp4"}}
                """;
        PollResult done = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, done.getStatus());
        assertEquals(2, http.pollCount);
    }

    @Test
    void rememberTaskIdResolvesVideoIdBeforeAgnesapi() {
        http.retrieveBody = """
                {"id":"task_old","video_id":"video_real","status":"in_progress"}
                """;
        http.getBody = """
                {"video_id":"video_real","status":"completed","metadata":{"url":"https://cdn.example/out.mp4"}}
                """;
        http.download = new byte[]{9, 8, 7, 6};
        provider.remember("task_old", video("r2v"), "https://api.agnes-ai.cn/v1", "test-key");

        PollResult result = provider.poll("task_old");
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(http.lastRetrieveUri.toString().endsWith("/videos/task_old"));
        assertTrue(http.lastPollUri.toString().contains("video_id=video_real"));
        assertFalse(http.lastPollUri.toString().contains("video_id=task_old"));
    }

    @Test
    void submitI2vSendsKeyframeUrls() throws Exception {
        http.postBody = """
                {"video_id":"video_k","status":"queued"}
                """;
        storage.put("users/8/assets/first.png", TINY_PNG, "image/png");
        storage.put("users/8/assets/last.png", TINY_PNG, "image/png");

        provider.submit(video("i2v").setFrames(new FramePair("users/8/assets/first.png", "users/8/assets/last.png")));
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("keyframe", body.get("mode").asText());
        assertTrue(body.get("first_frame").asText().startsWith("data:image/png;base64,"));
        assertTrue(body.get("last_frame").asText().startsWith("data:image/png;base64,"));
    }

    @Test
    void submitR2vRejectsVideoReferenceOnFlash() {
        storage.put("users/8/assets/b.mp4", new byte[]{1, 2, 3, 4}, "video/mp4");
        GenerateRequest request = video("r2v")
                .setReferences(List.of(new ReferenceAsset("video", "users/8/assets/b.mp4")));
        BizException error = assertThrows(BizException.class, () -> provider.submit(request));
        assertTrue(error.getMessage().contains("不支持参考视频"));
    }

    @Test
    void submitR2vSendsPictureReferences() throws Exception {
        http.postBody = """
                {"video_id":"video_r","status":"queued"}
                """;
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");
        provider.submit(video("r2v").setReferences(List.of(new ReferenceAsset("image", "users/8/assets/a.png"))));
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("reference", body.get("mode").asText());
        assertTrue(body.get("prompt").asText().startsWith("<Picture 1>"));
        assertEquals(1, body.get("images").size());
        assertFalse(body.has("videos"));
    }

    @Test
    void refusesOfficialSeedanceInsteadOfHttp() {
        GenerateRequest request = video("t2v")
                .setAdapterKey(null)
                .setModelKey("doubao-seedance-2.0-fast");
        BizException ex = assertThrows(BizException.class, () -> provider.submit(request));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("不能走 Agnes"));
        assertEquals(null, http.lastUri);
    }

    @Test
    void refusesToPollArkJobId() {
        PollResult result = provider.poll("cgt-20260912190239-nwnfq");
        assertEquals(PollResult.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("不能走 Agnes"));
        assertEquals(null, http.lastGetUri);
    }

    @Test
    void rememberSkipsOfficialSeedanceAndPollDoesNotHttp() {
        GenerateRequest request = video("t2v")
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_0)
                .setModelKey("doubao-seedance-2.0-fast");
        provider.remember("cgt-ark-job", request, "https://api.agnes-ai.cn/v1", "test-key");
        PollResult result = provider.poll("cgt-ark-job");
        assertEquals(PollResult.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("不能走 Agnes"));
        assertEquals(null, http.lastGetUri);
    }

    @Test
    void rememberAllowsVideoPollAfterRestart() throws Exception {
        http.getBody = """
                {"video_id":"video_recover","status":"completed","metadata":{"url":"https://cdn.example/out.mp4"}}
                """;
        http.download = new byte[]{9, 8, 7, 6};
        provider.remember("video_recover", video("t2v"), "https://api.agnes-ai.cn/v1", "test-key");
        PollResult result = provider.poll("video_recover");
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
    }

    private static GenerateRequest image(String mode) {
        return new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("image")
                .setAdapterKey(AdapterCatalog.AGNES_IMAGE_2_5)
                .setModelKey("agnes-image-2.5-flash")
                .setMode(mode)
                .setPrompt("发光浮空城市")
                .setResolution("1K")
                .setRatio("16:9")
                .setEndpoint("https://api.agnes-ai.cn/v1")
                .setApiKey("sk-agnes");
    }

    private static GenerateRequest video(String mode) {
        return new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("video")
                .setAdapterKey(AdapterCatalog.AGNES_VIDEO_2_5)
                .setModelKey("agnes-video-2.5-flash")
                .setMode(mode)
                .setPrompt("雨后的未来城市街道")
                .setResolution("720P")
                .setRatio("16:9")
                .setDurationSec(5)
                .setEndpoint("https://api.agnes-ai.cn/v1")
                .setApiKey("sk-agnes");
    }

    private static final class FakeHttp implements AgnesHttp {
        private String postBody = "{}";
        private String getBody = "{\"status\":\"in_progress\"}";
        private String retrieveBody;
        private byte[] download = TINY_PNG;
        private URI lastUri;
        private URI lastGetUri;
        private URI lastPollUri;
        private URI lastRetrieveUri;
        private String lastJson;
        private int pollCount;
        private int retrieveCount;
        private BizException pollError;
        private final List<URI> posts = new ArrayList<>();

        @Override
        public String postJson(URI uri, String bearerToken, String jsonBody) {
            lastUri = uri;
            lastJson = jsonBody;
            posts.add(uri);
            return postBody;
        }

        @Override
        public String getJson(URI uri, String bearerToken) {
            lastGetUri = uri;
            if (uri.toString().contains("/agnesapi")) {
                lastPollUri = uri;
                pollCount++;
                if (pollError != null) {
                    throw pollError;
                }
                return getBody;
            }
            lastRetrieveUri = uri;
            retrieveCount++;
            if (retrieveBody != null) {
                return retrieveBody;
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
