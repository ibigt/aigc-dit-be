package cn.aigc.dit.generate.dashscope;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AudioReferenceCapabilities;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProvider;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.ReferenceAsset;
import cn.aigc.dit.generate.PingSupport;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DashScopeGenerateProvider implements GenerateProvider {

    public static final String KEY = "dashscope";

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Dashscope properties;
    private final DashScopeHttp http;
    private final ObjectMapper objectMapper;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Autowired
    public DashScopeGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkDashScopeHttp(properties.getDashscope()), new ObjectMapper());
    }

    DashScopeGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                              DashScopeHttp http, ObjectMapper objectMapper) {
        this.objectStorage = objectStorage;
        this.properties = properties.getDashscope();
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
        String apiBase = DashScopeUrls.apiBase(request.getEndpoint(), properties.getBaseUrl());
        String mode = GenerateMode.canonical(safe(request.getMode()));
        List<DashScopeRequestMapper.MediaItem> mediaItems = List.of();
        if (GenerateMode.I2V.equalsIgnoreCase(mode)) {
            if (request.getFrames() != null) {
                java.util.ArrayList<DashScopeRequestMapper.MediaItem> frames = new java.util.ArrayList<>();
                if (StringUtils.hasText(request.getFrames().getFirstFrameKey())) {
                    frames.add(new DashScopeRequestMapper.MediaItem(
                            "first_frame", mediaUri(request.getFrames().getFirstFrameKey())));
                }
                if (StringUtils.hasText(request.getFrames().getLastFrameKey())) {
                    frames.add(new DashScopeRequestMapper.MediaItem(
                            "last_frame", mediaUri(request.getFrames().getLastFrameKey())));
                }
                mediaItems = frames;
            }
        }
        if (GenerateMode.R2V.equalsIgnoreCase(mode)) {
            mediaItems = request.getReferences() == null ? List.of() : request.getReferences().stream()
                    .filter(reference -> reference != null && StringUtils.hasText(reference.getStorageKey()))
                    .map(reference -> new DashScopeRequestMapper.MediaItem(
                            mediaItemType(reference), mediaUri(reference.getStorageKey())))
                    .toList();
        }
        Map<String, Object> body = DashScopeRequestMapper.toCreateBody(request, mediaItems);
        log.info("DashScope posting taskId={}, model={}, mode={}, endpoint={}",
                request.getTaskId(), DashScopeRequestMapper.modelName(request), mode, apiBase);
        JsonNode response = postJson(DashScopeUrls.toUri(DashScopeUrls.createTask(apiBase)), apiKey, body, true);
        String status = DashScopeResponses.status(response);
        if (DashScopeResponses.failed(status)) {
            throw new BizException(ErrorCode.INTERNAL,
                    firstNonBlank(DashScopeResponses.errorMessage(response), "DashScope 提交失败"));
        }
        String jobId = DashScopeResponses.taskId(response);
        if (!StringUtils.hasText(jobId)) {
            throw new BizException(ErrorCode.INTERNAL, "DashScope 未返回任务 ID");
        }
        jobs.put(jobId, new Job(request, apiBase, apiKey));
        log.info("DashScope submit jobId={}, taskId={}, model={}, mode={}",
                jobId, request.getTaskId(), DashScopeRequestMapper.modelName(request), mode);
        return SubmitResult.accepted(jobId);
    }

    public void remember(String jobId, GenerateRequest request, String apiBase, String apiKey) {
        if (!StringUtils.hasText(jobId) || request == null) {
            return;
        }
        String base = DashScopeUrls.apiBase(apiBase, properties.getBaseUrl());
        jobs.computeIfAbsent(jobId, id -> new Job(request, base, apiKey));
    }

    @Override
    public PollResult poll(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            return PollResult.failed(jobId, "DashScope 任务不存在");
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
            return pollJob(job, jobId);
        } finally {
            synchronized (job) {
                job.pollInFlight = false;
            }
        }
    }

    private PollResult pollJob(Job job, String jobId) {
        try {
            JsonNode response = getJson(DashScopeUrls.toUri(DashScopeUrls.queryTask(job.apiBase, jobId)), job.apiKey);
            String status = DashScopeResponses.status(response);
            if (DashScopeResponses.succeeded(status)) {
                String url = DashScopeResponses.videoUrl(response);
                if (!StringUtils.hasText(url)) {
                    job.emptyUrlCount++;
                    if (job.emptyUrlCount >= 3) {
                        return PollResult.failed(jobId, "DashScope 成功但未返回成片 URL");
                    }
                    return PollResult.running(jobId, Math.max(90, job.lastProgress));
                }
                job.outputKeys = ingestUrl(job.request, url);
                return PollResult.succeeded(jobId, job.outputKeys);
            }
            if (DashScopeResponses.failed(status)) {
                return PollResult.failed(jobId,
                        firstNonBlank(DashScopeResponses.errorMessage(response), "DashScope 生成失败"));
            }
            job.lastProgress = "RUNNING".equalsIgnoreCase(status) ? 60 : 20;
            scheduleNextPoll(job);
            return PollResult.running(jobId, job.lastProgress);
        } catch (BizException e) {
            if (rateLimited(e)) {
                job.consecutive429 = Math.min(6, job.consecutive429 + 1);
                long backoffMillis = 1000L * properties.getRateLimitBackoffSeconds() * (1L << (job.consecutive429 - 1));
                job.nextPollAtMillis = System.currentTimeMillis() + Math.min(60_000L, backoffMillis);
                return PollResult.running(jobId, Math.max(20, job.lastProgress));
            }
            if (transientPollError(e)) {
                job.transientErrors++;
                if (job.transientErrors >= 3) {
                    throw e;
                }
                scheduleNextPoll(job);
                return PollResult.running(jobId, Math.max(20, job.lastProgress));
            }
            throw e;
        }
    }

    @Override
    public void cancel(String jobId) {
        Job job = jobs.remove(jobId);
        if (job == null) {
            return;
        }
        try {
            postJson(DashScopeUrls.toUri(DashScopeUrls.cancelTask(job.apiBase, jobId)), job.apiKey, Map.of(), false);
        } catch (BizException e) {
            log.info("DashScope cancel jobId={} ignored: {}", jobId, e.getMessage());
        }
    }

    @Override
    public void ping(GenerateRequest request) {
        String apiBase = DashScopeUrls.apiBase(request.getEndpoint(), properties.getBaseUrl());
        try {
            getJson(DashScopeUrls.toUri(DashScopeUrls.queryTask(apiBase, PingSupport.PROBE_TASK_ID)),
                    resolveApiKey(request));
        } catch (BizException e) {
            PingSupport.classifyQueryProbe(e);
        }
    }

    private void assertSupported(GenerateRequest request) {
        String mode = GenerateMode.canonical(safe(request.getMode()));
        if (GenerateMode.T2V.equalsIgnoreCase(mode)) {
            if (!StringUtils.hasText(request.getPrompt())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文生视频提示词不能为空");
            }
            return;
        }
        if (GenerateMode.I2V.equalsIgnoreCase(mode)) {
            if (request.getFrames() == null || !request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "首尾帧模式至少需要首帧或尾帧");
            }
            return;
        }
        if (GenerateMode.R2V.equalsIgnoreCase(mode)) {
            List<ReferenceAsset> references = request.getReferences() == null ? List.of() : request.getReferences();
            long imageCount = references.stream()
                    .filter(reference -> reference != null && "image".equals(referenceType(reference))
                            && StringUtils.hasText(reference.getStorageKey()))
                    .count();
            long videoCount = references.stream()
                    .filter(reference -> reference != null && "video".equals(referenceType(reference))
                            && StringUtils.hasText(reference.getStorageKey()))
                    .count();
            long audioCount = references.stream()
                    .filter(DashScopeGenerateProvider::isAudioReference)
                    .filter(reference -> reference != null && StringUtils.hasText(reference.getStorageKey()))
                    .count();
            AudioReferenceCapabilities audio = AudioReferenceCapabilities.resolve(
                    request.getAdapterKey(), request.getProvider(), request.getCategory(), request.getModelKey());
            if (audioCount > 0 && !audio.supported()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "当前视频模型不支持上传参考音频");
            }
            if (audioCount > audio.maxReferences()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "当前视频模型最多支持 " + audio.maxReferences() + " 段参考音频");
            }
            if (imageCount > DashScopeRequestMapper.MAX_REFERENCE_IMAGES) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "参考图最多 " + DashScopeRequestMapper.MAX_REFERENCE_IMAGES + " 张");
            }
            if (videoCount > DashScopeRequestMapper.MAX_REFERENCE_VIDEOS) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "参考视频最多 " + DashScopeRequestMapper.MAX_REFERENCE_VIDEOS + " 段");
            }
            if (imageCount + videoCount == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频至少需要一张参考图或一段参考视频");
            }
            if (request.getFrames() != null && request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频请使用参考素材，不要同时提交首尾帧");
            }
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "Wan 3 通道支持 t2v / r2v / i2v，请调整生成模式");
    }

    private static boolean isAudioReference(ReferenceAsset reference) {
        return reference != null && "audio".equals(referenceType(reference));
    }

    private static String referenceType(ReferenceAsset reference) {
        String type = reference == null || reference.getType() == null
                ? "" : reference.getType().toLowerCase(Locale.ROOT);
        if (type.startsWith("video")) {
            return "video";
        }
        if (type.startsWith("audio")) {
            return "audio";
        }
        return "image";
    }

    private static String mediaItemType(ReferenceAsset reference) {
        return switch (referenceType(reference)) {
            case "video" -> "reference_video";
            case "audio" -> "reference_audio";
            default -> "reference_image";
        };
    }

    private String mediaUri(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        if (!objectStorage.exists(storageKey)) {
                throw new BizException(ErrorCode.NOT_FOUND, "视频素材不存在: " + storageKey);
        }
        return objectStorage.presignGet(storageKey, Duration.ofHours(24));
    }

    private List<String> ingestUrl(GenerateRequest request, String url) {
        log.info("Downloading DashScope output taskId={} host={}", request.getTaskId(), hostOf(url));
        byte[] bytes = http.getBytes(DashScopeUrls.toUri(url));
        long userId = request.getUserId() == null ? 0L : request.getUserId();
        long taskId = request.getTaskId() == null ? 0L : request.getTaskId();
        String key = "users/" + userId + "/outputs/" + taskId + "/1.mp4";
        objectStorage.put(key, bytes, "video/mp4");
        return List.of(key);
    }

    private JsonNode postJson(java.net.URI uri, String apiKey, Map<String, Object> body, boolean asyncHeader) {
        try {
            return readTree(http.postJson(uri, apiKey, objectMapper.writeValueAsString(body), asyncHeader));
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 DashScope 请求失败");
        }
    }

    private JsonNode getJson(java.net.URI uri, String apiKey) {
        return readTree(http.getJson(uri, apiKey));
    }

    private JsonNode readTree(String raw) {
        try {
            return objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "解析 DashScope 响应失败");
        }
    }

    private String resolveApiKey(GenerateRequest request) {
        if (StringUtils.hasText(request.getApiKey())) {
            return request.getApiKey().trim();
        }
        if (StringUtils.hasText(properties.getApiKey())) {
            return properties.getApiKey().trim();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "请在管理端填写 DashScope API Key");
    }

    private void scheduleNextPoll(Job job) {
        int interval = Math.max(1, properties.getPollIntervalSeconds());
        job.nextPollAtMillis = System.currentTimeMillis() + interval * 1000L;
    }

    private static boolean transientPollError(BizException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("429") || message.contains("404") || message.contains("超时") || message.contains("HTTP 5");
    }

    private static boolean rateLimited(BizException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("429") || message.toLowerCase(Locale.ROOT).contains("rate limit");
    }

    private static String hostOf(String url) {
        try {
            return DashScopeUrls.toUri(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Job {
        private final GenerateRequest request;
        private final String apiBase;
        private final String apiKey;
        private List<String> outputKeys;
        private int lastProgress;
        private int emptyUrlCount;
        private int transientErrors;
        private int consecutive429;
        private long nextPollAtMillis;
        private boolean pollInFlight;

        private Job(GenerateRequest request, String apiBase, String apiKey) {
            this.request = request;
            this.apiBase = apiBase;
            this.apiKey = apiKey;
        }
    }
}
