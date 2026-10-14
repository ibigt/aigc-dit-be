package cn.aigc.dit.generate.dashscope;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.FramePair;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashScopeGenerateProviderTest {

    private FakeHttp http;
    private DashScopeGenerateProvider provider;
    private LocalObjectStorage storage;
    private GenerateProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        properties = new GenerateProperties();
        properties.getDashscope().setApiKey("test-key");
        properties.getDashscope().setPollIntervalSeconds(0);
        properties.getDashscope().setRateLimitBackoffSeconds(0);
        http = new FakeHttp();
        storage = new LocalObjectStorage(tempDir);
        provider = new DashScopeGenerateProvider(storage, properties, http, mapper);
    }

    @Test
    void submitT2vThenPollSucceededDownloadsMp4() throws Exception {
        http.postBody = """
                {"output":{"task_id":"task-1","task_status":"PENDING"}}
                """;
        http.getBody = """
                {"output":{"task_id":"task-1","task_status":"SUCCEEDED","video_url":"https://cdn.example/out.mp4"}}
                """;
        http.download = new byte[]{1, 2, 3, 4};

        SubmitResult submitted = provider.submit(video("t2v"));
        assertEquals("task-1", submitted.getJobId());
        assertTrue(http.asyncHeader);
        assertTrue(http.lastPostUri.toString().endsWith("/services/aigc/video-generation/video-synthesis"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
        assertTrue(http.lastGetUri.toString().endsWith("/tasks/task-1"));
    }

    @Test
    void submitI2vInlinesFramesAsBase64() throws Exception {
        http.postBody = """
                {"output":{"task_id":"task-2","task_status":"PENDING"}}
                """;
        storage.put("first.png", new byte[]{1, 2, 3}, "image/png");
        storage.put("last.png", new byte[]{4, 5, 6}, "image/png");

        provider.submit(video("i2v").setFrames(new FramePair("first.png", "last.png")));
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("first_frame", body.path("input").path("media").get(0).path("type").asText());
        String firstUrl = body.path("input").path("media").get(0).path("url").asText();
        assertTrue(firstUrl.startsWith("data:image/png;base64,"), firstUrl);
        assertEquals("AQID", firstUrl.substring("data:image/png;base64,".length()));
        assertEquals("last_frame", body.path("input").path("media").get(1).path("type").asText());
        String lastUrl = body.path("input").path("media").get(1).path("url").asText();
        assertTrue(lastUrl.startsWith("data:image/png;base64,"), lastUrl);
        assertEquals("BAUG", lastUrl.substring("data:image/png;base64,".length()));
    }

    @Test
    void submitR2vInlinesReferencesAsBase64() throws Exception {
        http.postBody = """
                {"output":{"task_id":"task-r2v","task_status":"PENDING"}}
                """;
        storage.put("ref-a.png", new byte[]{10, 20}, "image/png");
        storage.put("ref-b.jpg", new byte[]{30, 40}, "image/jpeg");

        provider.submit(video("r2v").setReferences(java.util.List.of(
                new cn.aigc.dit.generate.ReferenceAsset("image", "ref-a.png"),
                new cn.aigc.dit.generate.ReferenceAsset("image", "ref-b.jpg"))));

        JsonNode body = mapper.readTree(http.lastJson);
        JsonNode media = body.path("input").path("media");
        assertEquals(2, media.size());
        assertEquals("reference_image", media.get(0).path("type").asText());
        String refAUrl = media.get(0).path("url").asText();
        assertTrue(refAUrl.startsWith("data:image/png;base64,"), refAUrl);
        assertEquals("ChQ=", refAUrl.substring("data:image/png;base64,".length()));
        assertEquals("reference_image", media.get(1).path("type").asText());
        String refBUrl = media.get(1).path("url").asText();
        assertTrue(refBUrl.startsWith("data:image/jpeg;base64,"), refBUrl);
        assertEquals("Hig=", refBUrl.substring("data:image/jpeg;base64,".length()));
        assertFalse(body.path("input").has("first_frame_url"));
        assertFalse(body.path("input").has("last_frame_url"));
    }

    @Test
    void submitR2vSeparatesImageAndAudioReferencesAsBase64() throws Exception {
        http.postBody = """
                {"output":{"task_id":"task-r2v-audio","task_status":"PENDING"}}
                """;
        storage.put("ref-a.png", new byte[]{1}, "image/png");
        storage.put("ref-voice.mp3", new byte[]{2}, "audio/mpeg");

        provider.submit(video("r2v").setReferences(java.util.List.of(
                new cn.aigc.dit.generate.ReferenceAsset("image", "ref-a.png"),
                new cn.aigc.dit.generate.ReferenceAsset("audio", "ref-voice.mp3"))));

        JsonNode body = mapper.readTree(http.lastJson);
        JsonNode media = body.path("input").path("media");
        assertEquals(2, media.size());
        assertEquals("reference_image", media.get(0).path("type").asText());
        String imageUrl = media.get(0).path("url").asText();
        assertTrue(imageUrl.startsWith("data:image/png;base64,"), imageUrl);
        assertEquals("reference_audio", media.get(1).path("type").asText());
        String audioUrl = media.get(1).path("url").asText();
        assertTrue(audioUrl.startsWith("data:audio/mpeg;base64,"), audioUrl);
    }

    @Test
    void rejectsR2vWithOnlyAudioReference() {
        storage.put("ref-voice.mp3", new byte[]{1}, "audio/mpeg");

        BizException error = assertThrows(BizException.class, () -> provider.submit(video("r2v")
                .setReferences(java.util.List.of(
                        new cn.aigc.dit.generate.ReferenceAsset("audio", "ref-voice.mp3")))));
        assertEquals("参考生视频至少需要一张参考图或一段参考视频", error.getMessage());
    }

    @Test
    void rejectsR2vWithMoreThanFiveAudioReferences() {
        storage.put("ref-a.png", new byte[]{1}, "image/png");
        for (int index = 1; index <= 6; index++) {
            storage.put("ref-voice-" + index + ".mp3", new byte[]{1}, "audio/mpeg");
        }

        BizException error = assertThrows(BizException.class, () -> provider.submit(video("r2v")
                .setReferences(java.util.List.of(
                        new cn.aigc.dit.generate.ReferenceAsset("image", "ref-a.png"),
                        new cn.aigc.dit.generate.ReferenceAsset("audio", "ref-voice-1.mp3"),
                        new cn.aigc.dit.generate.ReferenceAsset("audio", "ref-voice-2.mp3"),
                        new cn.aigc.dit.generate.ReferenceAsset("audio", "ref-voice-3.mp3"),
                        new cn.aigc.dit.generate.ReferenceAsset("audio", "ref-voice-4.mp3"),
                        new cn.aigc.dit.generate.ReferenceAsset("audio", "ref-voice-5.mp3"),
                        new cn.aigc.dit.generate.ReferenceAsset("audio", "ref-voice-6.mp3")))));
        assertEquals("当前视频模型最多支持 5 段参考音频", error.getMessage());
    }

    @Test
    void pollFailedReturnsProviderMessage() {
        http.postBody = """
                {"output":{"task_id":"task-3","task_status":"PENDING"}}
                """;
        http.getBody = """
                {"output":{"task_id":"task-3","task_status":"FAILED","message":"sensitive content"}}
                """;
        SubmitResult submitted = provider.submit(video("t2v"));
        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.FAILED, result.getStatus());
        assertEquals("sensitive content", result.getErrorMessage());
    }

    @Test
    void pingClassifiesUnauthorized() {
        BizException unauthorized = new BizException(cn.aigc.dit.common.exception.ErrorCode.INTERNAL,
                "DashScope 查询 HTTP 401: InvalidApiKey");
        http.pollError = unauthorized;
        BizException error = assertThrows(BizException.class, () -> provider.ping(video("t2v")));
        assertTrue(error.getMessage().contains("InvalidApiKey"));

        http.pollError = new BizException(cn.aigc.dit.common.exception.ErrorCode.INTERNAL,
                "DashScope 查询 HTTP 404: task not found");
        provider.ping(video("t2v"));
    }

    private static GenerateRequest video(String mode) {
        return new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("video")
                .setAdapterKey(AdapterCatalog.WAN3)
                .setModelKey("wan3.0-video")
                .setMode(mode)
                .setPrompt(mode.equals("t2v") ? "雨后的未来城市街道" : "")
                .setResolution("720P")
                .setRatio("16:9")
                .setDurationSec(5)
                .setEndpoint("https://dashscope.aliyuncs.com/api/v1")
                .setApiKey("sk-dashscope");
    }

    private static final class FakeHttp implements DashScopeHttp {
        private String postBody = "{}";
        private String getBody = """
                {"output":{"task_status":"RUNNING"}}
                """;
        private byte[] download = new byte[]{1};
        private BizException pollError;
        private String lastJson;
        private URI lastPostUri;
        private URI lastGetUri;
        private boolean asyncHeader;

        @Override
        public String postJson(URI uri, String bearerToken, String jsonBody, boolean async) {
            this.lastPostUri = uri;
            this.lastJson = jsonBody;
            this.asyncHeader = async;
            return postBody;
        }

        @Override
        public String getJson(URI uri, String bearerToken) {
            if (pollError != null) {
                throw pollError;
            }
            this.lastGetUri = uri;
            return getBody;
        }

        @Override
        public byte[] getBytes(URI uri) {
            return download;
        }
    }
}
