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
import cn.aigc.dit.generate.audio.AudioReferenceProcessor;
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
    static final long SAFE_AUDIO_TOTAL_DURATION_MILLIS = 14_800L;

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Ark properties;
    private final ArkHttp http;
    private final ObjectMapper objectMapper;
    private final AudioReferenceProcessor audioReferenceProcessor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Autowired
    public ArkGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                               AudioReferenceProcessor audioReferenceProcessor) {
        this(objectStorage, properties, new JdkArkHttp(properties.getArk()), new ObjectMapper(),
                audioReferenceProcessor);
    }

    ArkGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                        ArkHttp http, ObjectMapper objectMapper,
                        AudioReferenceProcessor audioReferenceProcessor) {
        this.objectStorage = objectStorage;
        this.properties = properties.getArk();
        this.http = http;
        this.objectMapper = objectMapper;
        this.audioReferenceProcessor = audioReferenceProcessor;
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
        List<String> audios = GenerateMode.R2V.equalsIgnoreCase(mode) ? mediaUris(request, "audio") : List.of();
        try {
            return createTask(request, apiKey, v3Base, mode, first, last, images, videos, audios);
        } catch (BizException e) {
            if (!ArkErrors.isRealPerson(e.getMessage())
                    || !GenerateMode.R2V.equalsIgnoreCase(mode)
                    || images.isEmpty()) {
                throw e;
            }
            if ((!videos.isEmpty() || !audios.isEmpty())
                    && (!videos.isEmpty() || ArkRequestMapper.supportsAudioOnly(request))) {
                log.warn("Seedance rejected reference images as real person, retrying without images taskId={}",
                        request.getTaskId());
                return createTask(request, apiKey, v3Base, GenerateMode.R2V,
                        null, null, List.of(), videos, audios);
            }
            log.warn("Seedance rejected reference images as real person, retrying text-only t2v taskId={}",
                    request.getTaskId());
            return createTask(request, apiKey, v3Base, GenerateMode.T2V,
                    null, null, List.of(), List.of(), List.of());
        }
    }

    private SubmitResult createTask(
            GenerateRequest request,
            String apiKey,
            String v3Base,
            String mode,
            String first,
            String last,
            List<String> images,
            List<String> videos,
            List<String> audios) {
        Map<String, Object> body = ArkRequestMapper.toCreateBody(request, first, last, images, videos, audios);
        log.info("Seedance posting taskId={}, model={}, mode={}, images={}, videos={}, audios={}, endpoint={}",
                request.getTaskId(), ArkRequestMapper.modelName(request), mode,
                images == null ? 0 : images.size(), videos == null ? 0 : videos.size(),
                audios == null ? 0 : audios.size(), v3Base);
        JsonNode response = postJson(ArkUrls.toUri(ArkUrls.createTask(v3Base)), apiKey, body);
        String status = ArkResponses.status(response);
        if (ArkResponses.failed(status)) {
            String err = firstNonBlank(ArkResponses.errorMessage(response), "Seedance 提交失败");
            throw new BizException(ArkErrors.isRealPerson(err) ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL, err);
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

    private JsonNode queryWithFallback(Job job, String jobId) {
        java.util.List<String> urls = ArkUrls.queryUrls(job.v3Base, jobId);
        BizException last = null;
        for (int i = 0; i < urls.size(); i++) {
            String queryUrl = urls.get(i);
            try {
                log.info("Seedance poll jobId={} url={}", jobId, queryUrl);
                JsonNode response = getJson(ArkUrls.toUri(queryUrl), job.apiKey);
                String nextBase = ArkUrls.baseOfQueryUrl(queryUrl);
                if (StringUtils.hasText(nextBase) && !nextBase.equalsIgnoreCase(job.v3Base)) {
                    log.info("Seedance poll switched base {} -> {}", job.v3Base, nextBase);
                    job.v3Base = nextBase;
                }
                return response;
            } catch (BizException e) {
                last = e;
                if (i + 1 < urls.size() && e.getMessage() != null && e.getMessage().contains("404")) {
                    log.warn("Seedance poll {} failed, try alternate: {}", queryUrl, e.getMessage());
                    continue;
                }
                throw e;
            }
        }
        throw last == null ? new BizException(ErrorCode.INTERNAL, "Seedance 查询失败") : last;
    }

    private PollResult pollVideo(Job job, String jobId) {
        try {
            JsonNode response = queryWithFallback(job, jobId);
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
        boolean hasAudio = request.getReferences() != null && request.getReferences().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(ref -> "audio".equals(refKind(ref)));
        if ((request.getPrompt() == null || request.getPrompt().isBlank()) && !hasAudio) {
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
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频至少需要图片、视频或音频之一");
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
        int audios = 0;
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            String kind = refKind(ref);
            if ("audio".equals(kind)) {
                audios++;
                if (audios > ArkRequestMapper.maxAudios(request)) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "参考音频最多 " + ArkRequestMapper.maxAudios(request) + " 段");
                }
            } else if ("video".equals(kind)) {
                videos++;
                if (videos > ArkRequestMapper.maxVideos(request)) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "参考视频最多 " + ArkRequestMapper.maxVideos(request) + " 段");
                }
            } else {
                images++;
                if (images > ArkRequestMapper.maxImages(request)) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "参考图最多 " + ArkRequestMapper.maxImages(request) + " 张");
                }
            }
        }
        if (images + videos + audios == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少需要一张参考图、一段参考视频或一段参考音频");
        }
        if (images + videos == 0 && !ArkRequestMapper.supportsAudioOnly(request)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Seedance 2.0 的参考音频必须搭配图片或视频");
        }
    }

    private List<String> mediaUris(GenerateRequest request, String kind) {
        List<String> values = new ArrayList<>();
        if (request.getReferences() == null) {
            return values;
        }
        long audioCount = "audio".equals(kind) ? referenceCount(request, kind) : 0L;
        long maxDurationMillis = audioCount > 0
                ? SAFE_AUDIO_TOTAL_DURATION_MILLIS / audioCount
                : 0L;
        if (audioCount > 0) {
            log.info("Seedance audio preprocessing taskId={}, references={}, perReferenceLimitMs={}, totalLimitMs={}",
                    request.getTaskId(), audioCount, maxDurationMillis, SAFE_AUDIO_TOTAL_DURATION_MILLIS);
        }
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            if (kind.equals(refKind(ref))) {
                values.add(mediaUri(ref.getStorageKey(), kind, maxDurationMillis));
            }
        }
        return values;
    }

    private static long referenceCount(GenerateRequest request, String kind) {
        return request.getReferences().stream()
                .filter(ref -> ref != null && StringUtils.hasText(ref.getStorageKey()))
                .filter(ref -> kind.equals(refKind(ref)))
                .count();
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
        return mediaUri(storageKey, null, 0L);
    }

    private String mediaUri(String storageKey, String kind, long maxDurationMillis) {
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        byte[] bytes = objectStorage.getBytes(storageKey);
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材文件为空");
        }
        String mime = contentTypeOf(storageKey, kind);
        if ("audio".equals(kind)) {
            AudioReferenceProcessor.ProcessedAudio processed = audioReferenceProcessor.process(
                    bytes, storageKey, maxDurationMillis);
            bytes = processed.bytes();
            mime = processed.contentType();
        }
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
        return message.contains("429")
                || message.contains("404")
                || message.contains("超时")
                || message.contains("HTTP 5");
    }

    private static boolean rateLimited(BizException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("429") || message.toLowerCase(Locale.ROOT).contains("rate limit");
    }

    private static String contentTypeOf(String storageKey, String kind) {
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
        if (name.endsWith(".wav")) {
            return "audio/wav";
        }
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".m4a") || name.endsWith(".aac")) {
            return "audio/mp4";
        }
        if ("audio".equals(kind)) {
            return "audio/mpeg";
        }
        if ("video".equals(kind)) {
            return "video/mp4";
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
        private volatile String v3Base;
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
