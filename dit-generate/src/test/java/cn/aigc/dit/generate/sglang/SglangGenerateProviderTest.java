package cn.aigc.dit.generate.sglang;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.generate.*;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SglangGenerateProviderTest {
    private GenerateRequest request() {
        return new GenerateRequest().setAdapterKey(AdapterCatalog.MINIMAX_H3_SGLANG).setCategory("video")
                .setMode("t2v").setPrompt("cat running").setEndpoint("http://localhost:30010")
                .setUserId(7L).setTaskId(99L);
    }
    private byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void mapsTextRequestAndRoutesIndependentOfOfficialMiniMax() {
        SglangGenerateProvider provider = new SglangGenerateProvider(mock(ObjectStorage.class), mock(SglangHttp.class));
        Map<String, Object> body = provider.createBody(request().setAdapterConfig(Map.of("seed", 1101)));
        assertEquals("t2va", body.get("task"));
        assertEquals("MiniMaxAI/MiniMax-H3", body.get("model"));
        assertEquals(Map.of("short_edge", 768, "aspect_ratio", "16:9", "duration_seconds", 5.0), body.get("target"));
        assertEquals(1101, body.get("seed"));
        assertEquals("sglang", new GenerateProviderRegistry(List.of(provider))
                .resolveByAdapter(AdapterCatalog.MINIMAX_H3_SGLANG, "官方API", "video", "MiniMax-H3").providerKey());
        assertTrue(AdapterCatalog.adminVisible().stream().anyMatch(s -> s.key().equals(AdapterCatalog.MINIMAX_H3_SGLANG)));
        assertEquals("http://localhost:30010/v1", SglangGenerateProvider.base("http://localhost:30010/v1/videos/"));
    }

    @Test void submitsPollsDownloadsAuthenticatedVideoAndCachesStorageResult() throws Exception {
        ObjectStorage storage = mock(ObjectStorage.class);
        List<String> calls = new ArrayList<>();
        SglangHttp http = (method, uri, key, body) -> {
            calls.add(method + " " + uri.getPath());
            assertEquals("secret", key);
            if (method.equals("POST")) {
                try { assertEquals("t2va", new ObjectMapper().readTree(body).path("task").asText()); }
                catch (Exception e) { throw new AssertionError(e); }
                return json("{\"id\":\"video_1\",\"status\":\"queued\"}");
            }
            return uri.getPath().endsWith("/content") ? json("mp4") : json("{\"status\":\"completed\"}");
        };
        SglangGenerateProvider provider = new SglangGenerateProvider(storage, http);
        String id = provider.submit(request().setApiKey("secret")).getJobId();
        assertTrue(provider.poll(id).isSucceeded());
        assertTrue(provider.poll(id).isSucceeded());
        verify(storage, times(1)).put("users/7/outputs/99/1.mp4", json("mp4"), "video/mp4");
        assertEquals(List.of("POST /v1/videos", "GET /v1/videos/video_1", "GET /v1/videos/video_1/content"), calls);
    }

    @Test void restoresExistingJobWithoutSubmittingAgainAndReportsFailure() {
        SglangHttp http = (method, uri, key, body) -> {
            assertEquals("GET", method);
            return json("{\"status\":\"failed\",\"error\":{\"message\":\"GPU exhausted\"}}");
        };
        SglangGenerateProvider provider = new SglangGenerateProvider(mock(ObjectStorage.class), http);
        provider.remember("video_old", request());
        assertEquals("GPU exhausted", provider.poll("video_old").getErrorMessage());
    }

    @Test void mapsKeyframesAndMultimodalReferencesToSignedUris() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.presignGet("first.png", Duration.ofHours(24))).thenReturn("http://storage/first.png");
        SglangGenerateProvider provider = new SglangGenerateProvider(storage, mock(SglangHttp.class));
        Map<String, Object> body = provider.createBody(request().setMode("i2v")
                .setFrames(new FramePair().setFirstFrameKey("first.png").setLastFrameKey("https://storage/last.png")));
        assertEquals("fl2va", body.get("task"));
        List<?> conditions = (List<?>) body.get("conditions");
        assertEquals(0, ((Map<?, ?>) conditions.get(0)).get("frame_index"));
        assertEquals(-1, ((Map<?, ?>) conditions.get(1)).get("frame_index"));
        body = provider.createBody(request().setMode("r2v").setReferences(List.of(new ReferenceAsset("audio", "https://storage/a.wav"))));
        assertEquals("ref2va", body.get("task"));
        assertEquals("audio", ((Map<?, ?>) ((List<?>) body.get("conditions")).get(0)).get("type"));
    }

    @Test void rejectsInvalidModesDurationsAndMultipleOutputs() {
        SglangGenerateProvider provider = new SglangGenerateProvider(mock(ObjectStorage.class), mock(SglangHttp.class));
        assertThrows(BizException.class, () -> provider.createBody(request().setMode("t2i")));
        assertThrows(BizException.class, () -> provider.createBody(request().setDurationSec(4)));
        assertThrows(BizException.class, () -> provider.createBody(request().setDurationSec(16)));
        assertThrows(BizException.class, () -> provider.createBody(request().setCopies(2)));
        assertThrows(BizException.class, () -> provider.createBody(request().setMode("i2v")));
    }

    @Test void mapsResolutionTiersToShortEdgeOverridingConfiguredDefault() {
        SglangGenerateProvider provider = new SglangGenerateProvider(mock(ObjectStorage.class), mock(SglangHttp.class));
        // 分辨率优先：2K→1440、数字档取值；无分辨率时回退 shortEdge 配置，再无则默认 768
        assertEquals(1440, targetEdge(provider.createBody(request().setResolution("2K").setAdapterConfig(Map.of("shortEdge", 768)))));
        assertEquals(2160, targetEdge(provider.createBody(request().setResolution("4K"))));
        assertEquals(768, targetEdge(provider.createBody(request().setResolution("768P"))));
        assertEquals(1080, targetEdge(provider.createBody(request().setResolution("1080P").setAdapterConfig(Map.of("shortEdge", 768)))));
        assertEquals(640, targetEdge(provider.createBody(request().setAdapterConfig(Map.of("shortEdge", 640)))));
        assertEquals(768, targetEdge(provider.createBody(request())));
    }

    private int targetEdge(Map<String, Object> body) {
        return (Integer) ((Map<?, ?>) body.get("target")).get("short_edge");
    }

    @Test void queuedJobStaysPendingAndEmptyCompletedVideoFails() {
        SglangGenerateProvider queued = new SglangGenerateProvider(mock(ObjectStorage.class),
                (method, uri, key, body) -> json("{\"status\":\"queued\"}"));
        queued.remember("q", request());
        assertEquals(PollResult.PENDING, queued.poll("q").getStatus());
        ObjectStorage storage = mock(ObjectStorage.class);
        SglangGenerateProvider empty = new SglangGenerateProvider(storage,
                (method, uri, key, body) -> uri.getPath().endsWith("/content") ? new byte[0] : json("{\"status\":\"completed\"}"));
        empty.remember("e", request());
        assertEquals(PollResult.FAILED, empty.poll("e").getStatus());
        verifyNoInteractions(storage);
    }

    @Test void pingIsReadOnlyAndTransientPollErrorsRetry() {
        List<String> calls = new ArrayList<>();
        SglangGenerateProvider provider = new SglangGenerateProvider(mock(ObjectStorage.class), (method, uri, key, body) -> {
            calls.add(uri.getPath());
            assertEquals("GET", method);
            if (uri.getPath().endsWith("/models")) return json("{\"data\":[]}");
            throw new BizException(cn.aigc.dit.common.exception.ErrorCode.INTERNAL, "SGLang HTTP 503");
        });
        provider.ping(request());
        provider.remember("v", request());
        assertEquals(PollResult.RUNNING, provider.poll("v").getStatus());
        assertEquals(PollResult.RUNNING, provider.poll("v").getStatus());
        assertEquals(List.of("/v1/models", "/v1/videos/v"), calls);
    }
}
