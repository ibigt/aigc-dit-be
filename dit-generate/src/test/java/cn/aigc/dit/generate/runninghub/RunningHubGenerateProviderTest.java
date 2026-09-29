package cn.aigc.dit.generate.runninghub;

import cn.aigc.dit.common.exception.BizException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunningHubGenerateProviderTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    private FakeHttp http;
    private RunningHubGenerateProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GenerateProperties properties = new GenerateProperties();
        properties.getRunninghub().setMaxCalls(2);
        properties.getRunninghub().setApiKey("test-key");
        properties.getRunninghub().setT2iPath("rhart-image-g-2/text-to-image");
        http = new FakeHttp();
        provider = new RunningHubGenerateProvider(
                new LocalObjectStorage(tempDir), properties, http, mapper);
    }

    @Test
    void submitMapsCanonicalToEconomyT2iAndPollStoresMinioObject() throws Exception {
        http.submitBody = "{\"taskId\":\"rh-1\",\"status\":\"RUNNING\"}";
        http.queryBody = """
                {"taskId":"rh-1","status":"SUCCESS","results":[{"url":"https://cdn.example/out.png","outputType":"png"}]}
                """;
        http.download = TINY_PNG;

        GenerateRequest request = t2i()
                .setResolution("2K")
                .setQuality("High")
                .setCopies(4)
                .setRatio("16:9");

        SubmitResult submitted = provider.submit(request);
        assertEquals("rh-1", submitted.getJobId());
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("1k", body.get("resolution").asText());
        assertEquals("16:9", body.get("aspectRatio").asText());
        assertTrue(http.lastJson.contains("prompt"));
        assertTrue(body.get("quality") == null, "economy 通道不传 quality");
        assertTrue(http.lastUri.toString().endsWith("/openapi/v2/rhart-image-g-2/text-to-image"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertEquals(1, result.getOutputKeys().size());
        assertTrue(result.getOutputKeys().get(0).endsWith(".png"));
        assertTrue(Files.size(tempDir.resolve(result.getOutputKeys().get(0))) > 10);
        assertEquals(1, provider.callCount());
    }

    @Test
    void officialPathSendsForcedLowQuality() throws Exception {
        http.submitBody = "{\"taskId\":\"rh-off\",\"status\":\"RUNNING\"}";
        GenerateRequest request = t2i().setAdapterConfig(Map.of(
                "t2iPath", "rhart-image-g-2-official/text-to-image"));
        provider.submit(request);
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("1k", body.get("resolution").asText());
        assertEquals("low", body.get("quality").asText());
        assertTrue(http.lastUri.toString().contains("rhart-image-g-2-official/text-to-image"));
    }

    @Test
    void submitMapsCanonicalToT2vAndPollStoresMp4() throws Exception {
        http.submitBody = "{\"taskId\":\"rh-v1\",\"status\":\"RUNNING\"}";
        http.queryBody = """
                {"taskId":"rh-v1","status":"SUCCESS","results":[{"url":"https://cdn.example/out.mp4","outputType":"mp4"}]}
                """;
        http.download = new byte[]{0, 0, 0, 1, 2, 3, 4, 5};

        GenerateRequest request = t2v()
                .setResolution("720P")
                .setDurationSec(5)
                .setRatio("16:9")
                .setAdapterConfig(Map.of("t2vPath", "rhart-video/sparkvideo-2.0/text-to-video"));

        SubmitResult submitted = provider.submit(request);
        assertEquals("rh-v1", submitted.getJobId());
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("720p", body.get("resolution").asText());
        assertEquals("5", body.get("duration").asText());
        assertEquals("16:9", body.get("ratio").asText());
        assertTrue(body.get("generateAudio").asBoolean());
        assertTrue(http.lastUri.toString().endsWith("/openapi/v2/rhart-video/sparkvideo-2.0/text-to-video"));

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertEquals(1, result.getOutputKeys().size());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
        assertTrue(Files.size(tempDir.resolve(result.getOutputKeys().get(0))) > 4);
        assertEquals(1, provider.callCount());
    }

    @Test
    void submitMapsCanonicalToI2iUploadsRefsAndStoresPng() throws Exception {
        http.submitBody = "{\"taskId\":\"rh-i2i\",\"status\":\"RUNNING\"}";
        http.queryBody = """
                {"taskId":"rh-i2i","status":"SUCCESS","results":[{"url":"https://cdn.example/out.png","outputType":"png"}]}
                """;
        http.download = TINY_PNG;
        new LocalObjectStorage(tempDir).put("users/8/assets/a.png", TINY_PNG, "image/png");

        GenerateRequest request = t2i()
                .setMode("i2i")
                .setResolution("2K")
                .setQuality("High")
                .setRatio("16:9")
                .setReferences(List.of(new ReferenceAsset("image", "users/8/assets/a.png")))
                .setAdapterConfig(Map.of("i2iPath", "rhart-image-g-2/image-to-image"));

        SubmitResult submitted = provider.submit(request);
        assertEquals("rh-i2i", submitted.getJobId());
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("1k", body.get("resolution").asText());
        assertEquals("16:9", body.get("aspectRatio").asText());
        assertEquals("https://cdn.example/a.png", body.get("imageUrls").get(0).asText());
        assertTrue(body.get("quality") == null, "economy 通道不传 quality");
        assertTrue(http.lastUri.toString().endsWith("/openapi/v2/rhart-image-g-2/image-to-image"));
        assertEquals(1, http.uploads.size());

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputKeys().get(0).endsWith(".png"));
        assertEquals(1, provider.callCount());
    }

    @Test
    void officialI2iPathSendsForcedLowQuality() throws Exception {
        http.submitBody = "{\"taskId\":\"rh-i2i-off\",\"status\":\"RUNNING\"}";
        new LocalObjectStorage(tempDir).put("users/8/assets/a.png", TINY_PNG, "image/png");
        GenerateRequest request = t2i()
                .setMode("i2i")
                .setReferences(List.of(new ReferenceAsset("image", "users/8/assets/a.png")))
                .setAdapterConfig(Map.of("i2iPath", "rhart-image-g-2-official/image-to-image"));
        provider.submit(request);
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("1k", body.get("resolution").asText());
        assertEquals("low", body.get("quality").asText());
        assertTrue(http.lastUri.toString().contains("rhart-image-g-2-official/image-to-image"));
    }

    @Test
    void rejectsI2iWithoutReferences() {
        GenerateRequest request = t2i().setMode("i2i");
        assertThrows(BizException.class, () -> provider.submit(request));
        assertEquals(0, provider.callCount());
        assertTrue(http.posts.isEmpty());
    }

    @Test
    void submitMapsCanonicalToR2vUploadsRefsAndStoresMp4() throws Exception {
        http.submitBody = "{\"taskId\":\"rh-r2v\",\"status\":\"RUNNING\"}";
        http.queryBody = """
                {"taskId":"rh-r2v","status":"SUCCESS","results":[{"url":"https://cdn.example/out.mp4","outputType":"mp4"}]}
                """;
        http.download = new byte[]{0, 0, 0, 1, 2, 3, 4, 5};
        new LocalObjectStorage(tempDir).put("users/8/assets/a.png", TINY_PNG, "image/png");
        new LocalObjectStorage(tempDir).put("users/8/assets/b.mp4", new byte[]{1, 2, 3, 4}, "video/mp4");

        GenerateRequest request = t2v()
                .setMode("r2v")
                .setResolution("720P")
                .setDurationSec(5)
                .setRatio("16:9")
                .setReferences(List.of(
                        new ReferenceAsset("image", "users/8/assets/a.png"),
                        new ReferenceAsset("video", "users/8/assets/b.mp4")))
                .setAdapterConfig(Map.of("r2vPath", "rhart-video/sparkvideo-2.0/multimodal-video"));

        SubmitResult submitted = provider.submit(request);
        assertEquals("rh-r2v", submitted.getJobId());
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals("@Image 1 @Video 1 暗夜竹林中的白衣剑客", body.get("prompt").asText());
        assertEquals("https://cdn.example/a.png", body.get("imageUrls").get(0).asText());
        assertEquals("https://cdn.example/b.mp4", body.get("videoUrls").get(0).asText());
        assertTrue(http.lastUri.toString().endsWith("/openapi/v2/rhart-video/sparkvideo-2.0/multimodal-video"));
        assertEquals(2, http.uploads.size());

        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
        assertEquals(1, provider.callCount());
    }

    @Test
    void rejectsR2vWithoutReferences() {
        GenerateRequest request = t2v().setMode("r2v");
        assertThrows(BizException.class, () -> provider.submit(request));
        assertEquals(0, provider.callCount());
        assertTrue(http.posts.isEmpty());
    }

    @Test
    void maxCallsRejectsThirdSubmit() {
        http.submitBody = "{\"taskId\":\"rh-x\",\"status\":\"RUNNING\"}";
        provider.submit(t2i().setTaskId(1L));
        http.submitBody = "{\"taskId\":\"rh-y\",\"status\":\"RUNNING\"}";
        provider.submit(t2i().setTaskId(2L));
        BizException ex = assertThrows(BizException.class, () -> provider.submit(t2i().setTaskId(3L)));
        assertTrue(ex.getMessage().contains("上限"));
        assertEquals(2, provider.callCount());
        assertEquals(2, http.posts.size());
    }

    @Test
    void zeroMaxCallsDoesNotLimit() {
        GenerateProperties properties = new GenerateProperties();
        properties.getRunninghub().setMaxCalls(0);
        properties.getRunninghub().setApiKey("test-key");
        RunningHubGenerateProvider unlimited = new RunningHubGenerateProvider(
                new LocalObjectStorage(tempDir), properties, http, mapper);
        http.submitBody = "{\"taskId\":\"rh-a\",\"status\":\"RUNNING\"}";
        unlimited.submit(t2i().setTaskId(1L));
        http.submitBody = "{\"taskId\":\"rh-b\",\"status\":\"RUNNING\"}";
        unlimited.submit(t2i().setTaskId(2L));
        http.submitBody = "{\"taskId\":\"rh-c\",\"status\":\"RUNNING\"}";
        unlimited.submit(t2i().setTaskId(3L));
        assertEquals(3, unlimited.callCount());
        assertEquals(3, http.posts.size());
    }

    @Test
    void rememberAllowsPollAfterRestartWithoutResubmit() throws Exception {
        http.queryBody = """
                {"taskId":"rh-recover","status":"SUCCESS","results":[{"url":"https://cdn.example/out.mp4","outputType":"mp4"}]}
                """;
        http.download = TINY_PNG;
        provider.remember("rh-recover", t2v(), "https://www.runninghub.ai/openapi/v2", "test-key");
        PollResult result = provider.poll("rh-recover");
        assertEquals(PollResult.SUCCEEDED, result.getStatus());
        assertEquals(1, result.getOutputKeys().size());
        assertTrue(result.getOutputKeys().get(0).endsWith(".mp4"));
        assertEquals(0, provider.callCount());
    }

    @Test
    void pingUsesQueryEndpointAndSucceedsWhenTaskNotFound() {
        http.queryBody = "{\"code\":803,\"msg\":\"task not found\"}";
        provider.ping(new GenerateRequest()
                .setAdapterKey("gpt-image-2")
                .setCategory("image")
                .setProvider("RunningHub")
                .setEndpoint("https://www.runninghub.ai")
                .setApiKey("sk-rh"));
        assertEquals(1, http.queries.get());
        assertTrue(http.lastUri.toString().endsWith("/openapi/v2/query"));
        assertTrue(http.lastJson.contains(cn.aigc.dit.generate.PingSupport.PROBE_TASK_ID));
    }

    @Test
    void pingFailsWhenUnauthorized() {
        http.queryError = new BizException(cn.aigc.dit.common.exception.ErrorCode.INTERNAL,
                "RunningHub HTTP 401: apikey invalid");
        BizException error = assertThrows(BizException.class,
                () -> provider.ping(new GenerateRequest()
                        .setAdapterKey("gpt-image-2")
                        .setCategory("image")
                        .setProvider("RunningHub")
                        .setEndpoint("https://www.runninghub.ai")
                        .setApiKey("sk-bad")));
        assertTrue(error.getMessage().contains("apikey"));
    }

    @Test
    void submitSurfacesBalanceErrorInsteadOfMissingTaskId() {
        // RunningHub 在余额不足时返回 HTTP 200 + errorCode=605 + 空 taskId + 文案。
        // 必须把 errorMessage 暴露出来，否则只看到「未返回 taskId」会让运营误判为解析问题。
        http.submitBody = "{\"taskId\":\"\",\"status\":\"\",\"errorCode\":\"605\","
                + "\"errorMessage\":\"Your balance is insufficient. Please top up your account.\","
                + "\"results\":null}";
        BizException error = assertThrows(BizException.class, () -> provider.submit(t2i()));
        assertTrue(error.getMessage().contains("balance is insufficient"),
                "expected surfaced error message, got: " + error.getMessage());
    }

    @Test
    void submitSurfacesExplicitErrorEvenWithTaskIdPresent() {
        // 接口另一种——带 taskId 但 status=FAILED 也要把 errorMessage 抛出来。
        http.submitBody = "{\"taskId\":\"rh-bad\",\"status\":\"FAILED\","
                + "\"errorMessage\":\"prompt rejected by moderation\"}";
        BizException error = assertThrows(BizException.class, () -> provider.submit(t2v()));
        assertTrue(error.getMessage().contains("prompt rejected by moderation"),
                "expected surfaced error message, got: " + error.getMessage());
    }

    private static GenerateRequest t2i() {
        return new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("image")
                .setMode("t2i")
                .setPrompt("一只坐在月亮上的猫")
                .setEndpoint("https://www.runninghub.ai")
                .setApiKey("sk-from-model");
    }

    private static GenerateRequest t2v() {
        return new GenerateRequest()
                .setUserId(8L)
                .setTaskId(42L)
                .setCategory("video")
                .setMode("t2v")
                .setPrompt("暗夜竹林中的白衣剑客")
                .setEndpoint("https://www.runninghub.ai")
                .setApiKey("sk-from-model");
    }

    private static final class FakeHttp implements RunningHubHttp {
        private String submitBody;
        private String queryBody = "{\"taskId\":\"rh-1\",\"status\":\"RUNNING\"}";
        private byte[] download = TINY_PNG;
        private URI lastUri;
        private String lastJson;
        private final List<URI> posts = new ArrayList<>();
        private final List<String> uploads = new ArrayList<>();
        private final AtomicInteger queries = new AtomicInteger();
        private BizException queryError;

        @Override
        public String postJson(URI uri, String bearerToken, String jsonBody) {
            lastUri = uri;
            lastJson = jsonBody;
            posts.add(uri);
            if (uri.toString().endsWith("/query")) {
                queries.incrementAndGet();
                if (queryError != null) {
                    BizException err = queryError;
                    queryError = null;
                    throw err;
                }
                return queryBody;
            }
            return submitBody;
        }

        @Override
        public String uploadBinary(URI uri, String bearerToken, String filename, String contentType, byte[] bytes) {
            uploads.add(filename);
            return "{\"code\":0,\"data\":{\"download_url\":\"https://cdn.example/" + filename + "\"}}";
        }

        @Override
        public byte[] getBytes(URI uri) {
            return download;
        }
    }
}
