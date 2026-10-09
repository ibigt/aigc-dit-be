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
import cn.aigc.dit.generate.audio.AudioReferenceProcessor;
import cn.aigc.dit.generate.seedream.SeedreamOriginUrls;
import cn.aigc.dit.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private FakeAudioReferenceProcessor audioReferenceProcessor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        properties = new GenerateProperties();
        properties.getArk().setApiKey("test-key");
        properties.getArk().setPollIntervalSeconds(0);
        properties.getArk().setRateLimitBackoffSeconds(0);
        http = new FakeHttp();
        storage = new LocalObjectStorage(tempDir);
        audioReferenceProcessor = new FakeAudioReferenceProcessor();
        provider = new ArkGenerateProvider(storage, properties, http, mapper, audioReferenceProcessor);
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
    void submitR2vUsesFreshSeedreamLiteOriginUrl() throws Exception {
        http.postBody = """
                {"id":"cgt-origin","status":"queued"}
                """;
        String key = "users/8/outputs/101/1.png";
        storage.put(key, TINY_PNG, "image/png");
        String origin = "https://cdn.example/lite.png";
        SeedreamOriginUrls.save(storage, mapper, key, origin, Instant.now());

        provider.submit(video("r2v").setReferences(List.of(new ReferenceAsset("image", key))));
        JsonNode body = mapper.readTree(http.lastJson);
        assertEquals(origin, body.get("content").get(1).get("image_url").get("url").asText());
    }

    @Test
    void submitR2vFallsBackToBase64WhenOriginExpired() throws Exception {
        http.postBody = """
                {"id":"cgt-expired","status":"queued"}
                """;
        String key = "users/8/outputs/102/1.png";
        storage.put(key, TINY_PNG, "image/png");
        SeedreamOriginUrls.save(storage, mapper, key, "https://cdn.example/lite.png", Instant.parse("2020-01-01T00:00:00Z"));

        provider.submit(video("r2v").setReferences(List.of(new ReferenceAsset("image", key))));
        JsonNode body = mapper.readTree(http.lastJson);
        String url = body.get("content").get(1).get("image_url").get("url").asText();
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
    void submitSeedance25ReadsAudioFromStorageAndSendsBase64() throws Exception {
        http.postBody = "{\"id\":\"cgt-audio\",\"status\":\"queued\"}";
        storage.put("users/8/assets/voice.wav", new byte[]{1, 2, 3}, "audio/wav");

        provider.submit(video("r2v")
                .setPrompt("")
                .setReferences(List.of(new ReferenceAsset("audio", "users/8/assets/voice.wav"))));

        JsonNode body = mapper.readTree(http.lastJson);
        JsonNode audio = body.get("content").get(0);
        assertEquals("audio_url", audio.get("type").asText());
        assertEquals("reference_audio", audio.get("role").asText());
        assertEquals("data:audio/mpeg;base64,CQg=", audio.get("audio_url").get("url").asText());
        assertEquals(1, audioReferenceProcessor.callCount);
        assertEquals("users/8/assets/voice.wav", audioReferenceProcessor.lastSourceName);
        assertEquals(List.of(14_800L), audioReferenceProcessor.maxDurations);
    }

    @Test
    void submitSeedance20SharesAudioDurationBudgetAcrossReferences() {
        http.postBody = "{\"id\":\"cgt-audio-multi\",\"status\":\"queued\"}";
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");
        storage.put("users/8/assets/voice-1.mp3", new byte[]{1}, "audio/mpeg");
        storage.put("users/8/assets/voice-2.mp3", new byte[]{2}, "audio/mpeg");

        provider.submit(video("r2v")
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_0)
                .setModelKey("doubao-seedance-2-0")
                .setReferences(List.of(
                        new ReferenceAsset("image", "users/8/assets/a.png"),
                        new ReferenceAsset("audio", "users/8/assets/voice-1.mp3"),
                        new ReferenceAsset("audio", "users/8/assets/voice-2.mp3"))));

        assertEquals(List.of(7_400L, 7_400L), audioReferenceProcessor.maxDurations);
    }

    @Test
    void threeAudioReferencesStayWithinSafeTotalDuration() {
        http.postBody = "{\"id\":\"cgt-audio-three\",\"status\":\"queued\"}";
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");
        List<ReferenceAsset> references = new ArrayList<>();
        references.add(new ReferenceAsset("image", "users/8/assets/a.png"));
        for (int index = 1; index <= 3; index++) {
            String key = "users/8/assets/voice-" + index + ".mp3";
            storage.put(key, new byte[]{(byte) index}, "audio/mpeg");
            references.add(new ReferenceAsset("audio", key));
        }

        provider.submit(video("r2v")
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_0)
                .setModelKey("doubao-seedance-2-0")
                .setReferences(references));

        assertEquals(List.of(4_933L, 4_933L, 4_933L), audioReferenceProcessor.maxDurations);
        assertTrue(audioReferenceProcessor.maxDurations.stream().mapToLong(Long::longValue).sum()
                <= ArkGenerateProvider.SAFE_AUDIO_TOTAL_DURATION_MILLIS);
    }

    @Test
    void submitSeedance20RejectsAudioOnly() {
        storage.put("users/8/assets/voice.mp3", new byte[]{1}, "audio/mpeg");
        GenerateRequest request = video("r2v")
                .setAdapterKey(AdapterCatalog.SEEDANCE_2_0)
                .setModelKey("doubao-seedance-2-0-260128")
                .setReferences(List.of(new ReferenceAsset("audio", "users/8/assets/voice.mp3")));

        BizException error = org.junit.jupiter.api.Assertions.assertThrows(
                BizException.class, () -> provider.submit(request));
        assertTrue(error.getMessage().contains("必须搭配图片或视频"));
    }

    @Test
    void submitR2vFailsWhenReferenceLooksLikeRealPerson() {
        http.postError = new BizException(ErrorCode.BAD_REQUEST, ArkErrors.REAL_PERSON_MESSAGE);
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");

        BizException error = org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> provider.submit(
                video("r2v").setReferences(List.of(new ReferenceAsset("image", "users/8/assets/a.png")))));
        assertEquals(ArkErrors.REAL_PERSON_MESSAGE, error.getMessage());
        assertEquals(1, http.postCount);
    }

    @Test
    void submitR2vFailsRealPersonEvenWithAudioReference() {
        http.postError = new BizException(ErrorCode.BAD_REQUEST, ArkErrors.REAL_PERSON_MESSAGE);
        storage.put("users/8/assets/a.png", TINY_PNG, "image/png");
        storage.put("users/8/assets/voice.wav", new byte[]{1, 2, 3}, "audio/wav");

        BizException error = org.junit.jupiter.api.Assertions.assertThrows(BizException.class, () -> provider.submit(
                video("r2v").setReferences(List.of(
                        new ReferenceAsset("image", "users/8/assets/a.png"),
                        new ReferenceAsset("audio", "users/8/assets/voice.wav")))));
        assertEquals(ArkErrors.REAL_PERSON_MESSAGE, error.getMessage());
        assertEquals(1, http.postCount);
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
    void pollPlan404FallsBackToPaygV3() {
        http.postBody = """
                {"id":"cgt-fb","status":"queued"}
                """;
        http.pollErrorForHostPath = "/api/plan/v3/";
        http.pollError = new BizException(ErrorCode.BAD_REQUEST, "Seedance 查询 HTTP 404");
        http.getBody = """
                {"id":"cgt-fb","status":"running"}
                """;

        SubmitResult submitted = provider.submit(video("t2v")
                .setEndpoint("https://ark.cn-beijing.volces.com/api/plan/v3"));
        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, result.getStatus());
        assertEquals(2, http.pollCount);
        assertTrue(http.lastPollUri.toString().contains("/api/v3/contents/generations/tasks/cgt-fb"));
    }

    @Test
    void poll404KeepsRunningInsteadOfFailing() {
        http.postBody = """
                {"id":"cgt-404","status":"queued"}
                """;
        http.pollError = new BizException(ErrorCode.BAD_REQUEST, "Seedance 查询 HTTP 404");

        SubmitResult submitted = provider.submit(video("t2v"));
        PollResult result = provider.poll(submitted.getJobId());
        assertEquals(PollResult.RUNNING, result.getStatus());
        assertEquals(1, http.pollCount);
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

    @Test
    void pingUsesQueryTaskEndpointAndIgnoresNotFound() {
        ArkGenerateProvider provider = new ArkGenerateProvider(storage, properties,
                http, mapper, new FakeAudioReferenceProcessor());
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey("seedance-2.5")
                .setCategory("video")
                .setProvider("官方API")
                .setEndpoint("https://ark.cn-beijing.volces.com/api/v3")
                .setApiKey("sk-ark")
                .setModelKey("doubao-seedance-2-5-260628");
        provider.ping(request);
        assertTrue(http.lastPollUri.toString().contains("/api/v3/contents/generations/tasks/"));
        assertTrue(http.lastPollUri.toString().endsWith("/" + cn.aigc.dit.generate.PingSupport.PROBE_TASK_ID));
    }

    @Test
    void pingFailsWhenArkRejectsKeyWith401() {
        http.pollError = new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                "Seedance 查询 HTTP 401: 方舟拒绝了 API Key");
        ArkGenerateProvider provider = new ArkGenerateProvider(storage, properties,
                http, mapper, new FakeAudioReferenceProcessor());
        GenerateRequest request = new GenerateRequest()
                .setAdapterKey("seedance-2.5")
                .setCategory("video")
                .setProvider("官方API")
                .setEndpoint("https://ark.cn-beijing.volces.com/api/v3")
                .setApiKey("sk-bad");
        BizException error = assertThrows(BizException.class, () -> provider.ping(request));
        assertTrue(error.getMessage().contains("方舟拒绝了 API Key"));
    }

    private static final class FakeAudioReferenceProcessor implements AudioReferenceProcessor {
        private int callCount;
        private String lastSourceName;
        private final List<Long> maxDurations = new ArrayList<>();

        @Override
        public ProcessedAudio process(byte[] source, String sourceName, long maxDurationMillis) {
            callCount++;
            lastSourceName = sourceName;
            maxDurations.add(maxDurationMillis);
            return new ProcessedAudio(new byte[]{9, 8}, "audio/mpeg");
        }
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
        private int postCount;
        private BizException postError;
        private BizException pollError;
        private String pollErrorForHostPath;

        @Override
        public String postJson(URI uri, String bearerToken, String jsonBody) {
            postCount++;
            lastUri = uri;
            lastJson = jsonBody;
            if (postError != null) {
                BizException error = postError;
                postError = null;
                throw error;
            }
            return postBody;
        }

        @Override
        public String getJson(URI uri, String bearerToken) {
            lastGetUri = uri;
            lastPollUri = uri;
            pollCount++;
            if (pollError != null && (pollErrorForHostPath == null || uri.toString().contains(pollErrorForHostPath))) {
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
