package cn.aigc.dit.generate.comfyui;

import cn.aigc.dit.common.exception.BizException;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComfyUiGenerateProviderTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final byte[] TINY_MP4 = new byte[]{0, 0, 0, 32, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

    @TempDir
    Path tempDir;

    private FakeHttp http;
    private ComfyUiGenerateProvider provider;
    private LocalObjectStorage storage;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GenerateProperties properties = new GenerateProperties();
        properties.getComfyui().setBaseUrl("http://comfy.local:8188");
        http = new FakeHttp();
        storage = new LocalObjectStorage(tempDir);
        provider = new ComfyUiGenerateProvider(storage, properties, http, mapper);
    }

    @Test
    void submitT2vAndPollStoresMp4() throws Exception {
        http.promptResponse = "{\"prompt_id\":\"c1\",\"number\":1,\"node_errors\":{}}";
        http.historyResponse = """
                {"c1":{"status":{"status_str":"success","completed":true},"outputs":{"15":{"images":[{"filename":"dit_minimax_00001_.mp4","subfolder":"","type":"output"}]}}}}
                """;
        http.download = TINY_MP4;

        GenerateRequest request = video("t2v").setDurationSec(5).setRatio("16:9");
        SubmitResult submitted = provider.submit(request);
        assertEquals("c1", submitted.getJobId());

        JsonNode body = mapper.readTree(http.lastJson);
        JsonNode prompt = body.get("prompt");
        assertEquals("MiniMaxH3ImageToVideo", classType(prompt, "MiniMaxH3ImageToVideo"));
        JsonNode miniMax = nodeByClass(prompt, "MiniMaxH3ImageToVideo");
        assertEquals("一只橘猫在雨夜走路", miniMax.get("inputs").get("prompt").asText());
        assertEquals(864, miniMax.get("inputs").get("width").asInt());
        assertEquals(480, miniMax.get("inputs").get("height").asInt());
        assertEquals(124, miniMax.get("inputs").get("length").asInt());
        assertTrue(http.lastUri.toString().endsWith("/prompt"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertEquals(1, result.getOutputKeys().size());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
        assertTrue(Files.size(tempDir.resolve(result.getOutputKeys().get(0))) > 4);
        assertTrue(http.lastGetUri.toString().contains("dit_minimax_00001_.mp4"));
    }

    @Test
    void emptyNodeErrorsIsSuccess() {
        http.promptResponse = "{\"prompt_id\":\"c2\",\"node_errors\":{}}";
        SubmitResult submitted = provider.submit(video("t2v"));
        assertEquals("c2", submitted.getJobId());
    }

    @Test
    void submitR2vUploadsReferences() throws Exception {
        http.promptResponse = "{\"prompt_id\":\"r1\",\"node_errors\":{}}";
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");
        storage.put("users/8/assets/b.mp4", TINY_MP4, "video/mp4");

        GenerateRequest request = video("r2v")
                .setReferences(List.of(
                        new ReferenceAsset("image", "users/8/assets/a.png"),
                        new ReferenceAsset("video", "users/8/assets/b.mp4")));
        SubmitResult submitted = provider.submit(request);
        assertEquals("r1", submitted.getJobId());
        assertEquals(List.of("a.png", "b.mp4"), http.uploads);

        JsonNode prompt = mapper.readTree(http.lastJson).get("prompt");
        JsonNode miniMax = nodeByClass(prompt, "MiniMaxH3ReferenceToVideo");
        assertTrue(miniMax.get("inputs").get("prompt").asText().startsWith("<Picture 1> <Video 1>"));
        assertTrue(miniMax.get("inputs").has("ref_images.ref_image_0"));
        assertTrue(miniMax.get("inputs").has("ref_videos.ref_video_0"));
        assertEquals("MiniMaxH3ReferenceToVideo", miniMax.get("class_type").asText());
        assertEquals(ComfyUiMinimaxGraph.UNET_R2V, nodeByClass(prompt, "UNETLoader").get("inputs").get("unet_name").asText());
    }

    @Test
    void submitI2vUploadsFrames() throws Exception {
        http.promptResponse = "{\"prompt_id\":\"v1\",\"node_errors\":{}}";
        storage.put("users/8/assets/first.png", TINY_PNG, "image/png");
        storage.put("users/8/assets/last.png", TINY_PNG, "image/png");

        GenerateRequest request = video("i2v")
                .setFrames(new FramePair("users/8/assets/first.png", "users/8/assets/last.png"));
        provider.submit(request);
        assertEquals(List.of("first.png", "last.png"), http.uploads);
        JsonNode miniMax = nodeByClass(mapper.readTree(http.lastJson).get("prompt"), "MiniMaxH3ImageToVideo");
        assertTrue(miniMax.get("inputs").has("first_frame"));
        assertTrue(miniMax.get("inputs").has("last_frame"));
    }

    @Test
    void rejectsImageModes() {
        GenerateRequest request = video("t2i").setCategory("image").setMode("t2i");
        assertThrows(BizException.class, () -> provider.submit(request));
        assertTrue(http.posts.isEmpty());
    }

    @Test
    void rejectsR2vWithoutReferences() {
        assertThrows(BizException.class, () -> provider.submit(video("r2v")));
        assertTrue(http.posts.isEmpty());
    }

    @Test
    void pollTreatsTimeoutAsRunning() {
        http.promptResponse = "{\"prompt_id\":\"c7\",\"node_errors\":{}}";
        SubmitResult submitted = provider.submit(video("t2v"));
        http.historyError = new BizException(cn.aigc.dit.common.exception.ErrorCode.INTERNAL, "ComfyUI 请求超时");
        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, result.getStatus());
    }

    @Test
    void pollTreatsMissingHistoryAsRunning() {
        http.promptResponse = "{\"prompt_id\":\"c9\",\"node_errors\":{}}";
        SubmitResult submitted = provider.submit(video("t2v"));
        http.historyResponse = "{}";
        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, result.getStatus());
    }

    @Test
    void pollMapsExecutionError() {
        http.promptResponse = "{\"prompt_id\":\"c8\",\"node_errors\":{}}";
        SubmitResult submitted = provider.submit(video("t2v"));
        http.historyResponse = """
                {"c8":{"status":{"status_str":"error","completed":true,"messages":[["execution_error",{"exception_message":"CUDA out of memory"}]]},"outputs":{}}}
                """;
        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage().contains("CUDA out of memory"));
    }

    private static GenerateRequest video(String mode) {
        return new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("video")
                .setMode(mode)
                .setPrompt("一只橘猫在雨夜走路")
                .setEndpoint("http://172.200.0.237:8188");
    }

    private static String classType(JsonNode prompt, String type) {
        JsonNode node = nodeByClass(prompt, type);
        return node.get("class_type").asText();
    }

    private static JsonNode nodeByClass(JsonNode prompt, String type) {
        var fields = prompt.fields();
        while (fields.hasNext()) {
            JsonNode node = fields.next().getValue();
            if (type.equals(node.path("class_type").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing node " + type);
    }

    private static final class FakeHttp implements ComfyUiHttp {
        private String promptResponse = "{\"prompt_id\":\"c1\",\"node_errors\":{}}";
        private String historyResponse = "{}";
        private byte[] download = TINY_MP4;
        private URI lastUri;
        private URI lastGetUri;
        private String lastJson;
        private BizException historyError;
        private final List<URI> posts = new ArrayList<>();
        private final List<String> uploads = new ArrayList<>();

        @Override
        public String postJson(URI uri, String bearerToken, String jsonBody) {
            lastUri = uri;
            lastJson = jsonBody;
            posts.add(uri);
            if (uri.toString().endsWith("/interrupt")) {
                return "{}";
            }
            return promptResponse;
        }

        @Override
        public String getJson(URI uri, String bearerToken) {
            lastGetUri = uri;
            if (historyError != null) {
                throw historyError;
            }
            return historyResponse;
        }

        @Override
        public byte[] getBytes(URI uri, String bearerToken) {
            lastGetUri = uri;
            return download;
        }

        @Override
        public String uploadImage(URI uri, String bearerToken, String filename, String contentType, byte[] bytes) {
            uploads.add(filename);
            return "{\"name\":\"" + filename + "\",\"subfolder\":\"\",\"type\":\"input\"}";
        }
    }
}
