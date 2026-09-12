package cn.aigc.dit.generate.ark;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.FramePair;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProvider;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.ReferenceAsset;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 火山方舟 Seedance 官方 REST：{@code POST /contents/generations/tasks} 后轮询同一路径。
 */
@Slf4j
@Component
public class ArkGenerateProvider implements GenerateProvider {

    public static final String KEY = "ark";

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Ark properties;
    private final ArkHttp http;
    private final ObjectMapper objectMapper;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Autowired
    public ArkGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkArkHttp(properties.getArk()), new ObjectMapper());
    }

    ArkGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                        ArkHttp http, ObjectMapper objectMapper) {
        this.objectStorage = objectStorage;
        this.properties = properties.getArk();
        this.http = http;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerKey() {
        return KEY;
    }

    @Override
    public SubmitResult submit(GenerateRequest request) {
        assertSupported(request);
        String apiKey = resolveApiKey(request);
        String v3Base = ArkUrls.v3Base(request.getEndpoint(), properties.getBaseUrl());
        String mode = GenerateMode.canonical(safe(request.getMode()));
        String first = null;
        String last = null;
        List<String> images = List.of();
        if (GenerateMode.I2V.equalsIgnoreCase(mode)) {
            FramePair frames = request.getFrames();
            if (frames != null) {
                first = mediaUri(frames.getFirstFrameKey());
                last = mediaUri(frames.getLastFrameKey());
            }
        } else if (GenerateMode.R2V.equalsIgnoreCase(mode)) {
            images = mediaUris(request, "image");
        }
        List<String> videos = GenerateMode.R2V.equalsIgnoreCase(mode) ? mediaUris(request, "video") : List.of();
        Map<String, Object> body = ArkRequestMapper.toCreateBody(request, first, last, images, videos);
        log.info("Seedance posting taskId={}, model={}, mode={}, endpoint={}",
                request.getTaskId(), ArkRequestMapper.modelName(request), mode, v3Base);
        JsonNode response = postJson(ArkUrls.toUri(ArkUrls.createTask(v3Base)), apiKey, body);
        String status = ArkResponses.status(response);
        if (ArkResponses.failed(status)) {
            throw new BizException(ErrorCode.INTERNAL,
                    firstNonBlank(ArkResponses.errorMessage(response), "Seedance 提交失败"));
        }
        String jobId = ArkResponses.taskId(response);
        if (!StringUtils.hasText(jobId)) {
            throw new BizException(ErrorCode.INTERNAL, "Seedance 未返回任务 ID");
        }
        Job job = new Job(request, v3Base, apiKey);
        jobs.put(jobId, job);
        log.info("Seedance submit jobId={}, taskId={}, model={}, mode={}",
                jobId, request.getTaskId(), ArkRequestMapper.modelName(request), mode);
        return SubmitResult.accepted(jobId);
    }

    public void remember(String jobId, GenerateRequest request, String v3Base, String apiKey) {
        if (!StringUtils.hasText(jobId) || request == null) {
            return;
        }
        String base = ArkUrls.v3Base(v3Base, properties.getBaseUrl());
        jobs.computeIfAbsent(jobId, id -> new Job(request, base, apiKey));
    }

    @Override
    public PollResult poll(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            return PollResult.failed(jobId, "Seedance 任务不存在");
        }
        if (job.outputKeys != null) {
            return PollResult.succeeded(jobId, job.outputKeys);
        }
        synchronized (job) {
            long now = System.currentTimeMillis();
            if (job.pollInFlight || job.nextPollAtMillis > now) {
                return PollResult.running(jobId, Math.max(10, job.lastProgress));
            }
            job.pollInFlight = true;
        }
        try {
            return pollVideo(job, jobId);
        } finally {
            synchronized (job) {
                job.pollInFlight = false;
            }
        }
    }

    @Override
    public void cancel(String jobId) {
        jobs.remove(jobId);
        log.info("Seedance cancel jobId={} (remote cancel not implemented)", jobId);
    }

    private PollResult pollVideo(Job job, String jobId) {
        try {
            JsonNode response = getJson(ArkUrls.toUri(ArkUrls.queryTask(job.v3Base, jobId)), job.apiKey);
            String status = ArkResponses.status(response);
            job.consecutive429 = 0;
            if (ArkResponses.queued(status)) {
                job.lastProgress = Math.max(job.lastProgress, 10);
                scheduleNextPoll(job, pollIntervalMs());
                return PollResult.pending(jobId, job.lastProgress);
            }
            if (ArkResponses.failed(status)) {
                return PollResult.failed(jobId, firstNonBlank(ArkResponses.errorMessage(response), "Seedance 生成失败"));
            }
            if (!ArkResponses.succeeded(status)) {
                job.lastProgress = Math.max(job.lastProgress, 40);
                scheduleNextPoll(job, pollIntervalMs());
                return PollResult.running(jobId, job.lastProgress);
            }
            String url = ArkResponses.outputUrl(response);
            if (!StringUtils.hasText(url)) {
                job.emptyUrlCount++;
                if (job.emptyUrlCount >= 20) {
                    return PollResult.failed(jobId, "Seedance 任务已完成但未返回成片地址");
                }
                scheduleNextPoll(job, pollIntervalMs());
                return PollResult.running(jobId, Math.max(90, job.lastProgress));
            }
            job.emptyUrlCount = 0;
            job.outputKeys = ingestUrl(job.request, url.trim());
            return PollResult.succeeded(jobId, job.outputKeys);
        } catch (BizException e) {
            if (rateLimited(e)) {
                long backoff = rateLimitBackoffMs(++job.consecutive429);
                scheduleNextPoll(job, backoff);
                log.warn("Seedance poll rate-limited jobId={} backoffMs={}: {}", jobId, backoff, e.getMessage());
                return PollResult.running(jobId, Math.max(40, job.lastProgress));
            }
            if (transientPollError(e)) {
                scheduleNextPoll(job, pollIntervalMs());
                log.warn("Seedance poll retryable jobId={}: {}", jobId, e.getMessage());
                return PollResult.running(jobId, Math.max(40, job.lastProgress));
            }
            log.warn("Seedance poll/ingest failed jobId={}: {}", jobId, e.getMessage());
            return PollResult.failed(jobId, e.getMessage());
        }
    }

    private void scheduleNextPoll(Job job, long delayMs) {
        synchronized (job) {
            job.nextPollAtMillis = System.currentTimeMillis() + Math.max(0L, delayMs);
        }
    }

    private long pollIntervalMs() {
        return Math.max(0, properties.getPollIntervalSeconds()) * 1000L;
    }

    private long rateLimitBackoffMs(int consecutive) {
        int base = Math.max(1, properties.getRateLimitBackoffSeconds());
        int shift = Math.min(Math.max(0, consecutive - 1), 3);
        return Math.min(60_000L, base * 1000L * (1L << shift));
    }

    private void assertSupported(GenerateRequest request) {
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        String adapterKey = AdapterCatalog.resolveKey(
                request.getAdapterKey(), KEY, request.getCategory(), request.getModelKey());
        if (adapterKey != null && !AdapterCatalog.isArk(adapterKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前官方 API 通道不支持该适配协议");
        }
        if (adapterKey != null && !AdapterCatalog.supportsMode(adapterKey, request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Seedance 官方 API 仅支持文生视频（t2v）、参考生视频（r2v）与首尾帧（i2v）");
        }
        String mode = GenerateMode.canonical(safe(request.getMode()));
        if (GenerateMode.T2V.equalsIgnoreCase(mode)) {
            if (request.getReferences() != null && !request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "当前通道为文生视频（t2v），请改用支持 r2v 的模型或移除参考素材");
            }
            if (request.getFrames() != null && request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文生视频请不要同时提交首尾帧");
            }
            return;
        }
        if (GenerateMode.I2V.equalsIgnoreCase(mode)) {
            FramePair frames = request.getFrames();
            if (frames == null || !frames.hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "首尾帧模式至少需要首帧或尾帧");
            }
            return;
        }
        if (GenerateMode.R2V.equalsIgnoreCase(mode)) {
            if (request.getReferences() == null || request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频至少需要一张图片或一段视频");
            }
            if (request.getFrames() != null && request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频请使用参考素材，不要同时提交首尾帧");
            }
            assertRefs(request);
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "官方 API 通道不支持该生成模式");
    }

    private void assertRefs(GenerateRequest request) {
        int images = 0;
        int videos = 0;
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            String kind = refKind(ref);
            if ("audio".equals(kind)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "本期 Seedance 通道暂不支持参考音频");
            }
            if ("video".equals(kind)) {
                videos++;
                if (videos > ArkRequestMapper.MAX_VIDEOS) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "参考视频最多 " + ArkRequestMapper.MAX_VIDEOS + " 段");
                }
            } else {
                images++;
                if (images > ArkRequestMapper.MAX_IMAGES) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "参考图最多 " + ArkRequestMapper.MAX_IMAGES + " 张");
                }
            }
        }
        if (images + videos == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少需要一张参考图或一段参考视频");
        }
    }

    private List<String> mediaUris(GenerateRequest request, String kind) {
        List<String> values = new ArrayList<>();
        if (request.getReferences() == null) {
            return values;
        }
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            if (kind.equals(refKind(ref))) {
                values.add(mediaUri(ref.getStorageKey()));
            }
        }
        return values;
    }

    private static String refKind(ReferenceAsset ref) {
        String type = ref.getType() == null ? "" : ref.getType().toLowerCase(Locale.ROOT);
        if (AssetKind.VIDEO.equalsIgnoreCase(type) || type.startsWith("video")) {
            return "video";
        }
        if (type.startsWith("audio")) {
            return "audio";
        }
        String key = ref.getStorageKey().toLowerCase(Locale.ROOT);
        if (key.endsWith(".mp4") || key.endsWith(".webm") || key.endsWith(".mov")) {
            return "video";
        }
        if (key.endsWith(".mp3") || key.endsWith(".wav") || key.endsWith(".m4a") || key.endsWith(".aac")) {
            return "audio";
        }
        return "image";
    }

    private String mediaUri(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        byte[] bytes = objectStorage.getBytes(storageKey);
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材文件为空");
        }
        String mime = contentTypeOf(storageKey);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private List<String> ingestUrl(GenerateRequest request, String url) {
        log.info("Downloading Seedance output taskId={} host={}", request.getTaskId(), hostOf(url));
        byte[] bytes = http.getBytes(ArkUrls.toUri(url));
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.INTERNAL, "Seedance 成片为空");
        }
        long userId = request.getUserId() == null ? 0L : request.getUserId();
        long taskId = request.getTaskId() == null ? 0L : request.getTaskId();
        String key = "users/" + userId + "/outputs/" + taskId + "/1.mp4";
        objectStorage.put(key, bytes, "video/mp4");
        return List.of(key);
    }

    private JsonNode postJson(java.net.URI uri, String apiKey, Map<String, Object> body) {
        try {
            return readTree(http.postJson(uri, apiKey, objectMapper.writeValueAsString(body)));
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 Seedance 请求失败");
        }
    }

    private JsonNode getJson(java.net.URI uri, String apiKey) {
        return readTree(http.getJson(uri, apiKey));
    }

    private JsonNode readTree(String raw) {
        try {
            return objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "解析 Seedance 响应失败");
        }
    }

    private String resolveApiKey(GenerateRequest request) {
        String fromRequest = ArkKeys.normalize(request.getApiKey());
        if (StringUtils.hasText(fromRequest)) {
            return fromRequest;
        }
        String fromConfig = ArkKeys.normalize(properties.getApiKey());
        if (StringUtils.hasText(fromConfig)) {
            return fromConfig;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "请在管理端填写火山方舟 API Key");
    }

    private static boolean transientPollError(BizException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("429") || message.contains("超时") || message.contains("HTTP 5");
    }

    private static boolean rateLimited(BizException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("429") || message.toLowerCase(Locale.ROOT).contains("rate limit");
    }

    private static String contentTypeOf(String storageKey) {
        String name = storageKey.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".webm")) {
            return "video/webm";
        }
        if (name.endsWith(".mov")) {
            return "video/quicktime";
        }
        return "image/png";
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost() == null ? "unknown" : java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : (second == null ? "" : second);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Job {
        private final GenerateRequest request;
        private final String v3Base;
        private final String apiKey;
        private volatile List<String> outputKeys;
        private volatile long nextPollAtMillis;
        private volatile int consecutive429;
        private volatile int emptyUrlCount;
        private volatile int lastProgress = 10;
        private volatile boolean pollInFlight;

        private Job(GenerateRequest request, String v3Base, String apiKey) {
            this.request = request;
            this.v3Base = v3Base;
            this.apiKey = apiKey;
        }
    }
}
