package cn.aigc.dit.generate.agnes;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agnes 官方 REST 运输层。图片同步生成；视频异步提交后轮询 {@code /agnesapi}。
 */
@Slf4j
@Component
public class AgnesGenerateProvider implements GenerateProvider {

    public static final String KEY = "agnes";

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Agnes properties;
    private final AgnesHttp http;
    private final ObjectMapper objectMapper;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Autowired
    public AgnesGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkAgnesHttp(properties.getAgnes()), new ObjectMapper());
    }

    AgnesGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                          AgnesHttp http, ObjectMapper objectMapper) {
        this.objectStorage = objectStorage;
        this.properties = properties.getAgnes();
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
        String v1Base = AgnesUrls.v1Base(request.getEndpoint(), properties.getBaseUrl());
        String mode = GenerateMode.canonical(safe(request.getMode()));
        if (GenerateMode.T2I.equalsIgnoreCase(mode) || GenerateMode.I2I.equalsIgnoreCase(mode)) {
            return submitImage(request, v1Base, apiKey);
        }
        return submitVideo(request, v1Base, apiKey);
    }

    public void remember(String jobId, GenerateRequest request, String v1Base, String apiKey) {
        if (!StringUtils.hasText(jobId) || request == null) {
            return;
        }
        if (isOfficialSeedance(request)) {
            log.warn("Agnes skip remember official seedance jobId={} modelKey={}", jobId, request.getModelKey());
            return;
        }
        String base = AgnesUrls.v1Base(v1Base, properties.getBaseUrl());
        jobs.computeIfAbsent(jobId, id -> new Job(request, base, apiKey, AgnesRequestMapper.modelName(request), id));
    }

    @Override
    public PollResult poll(String jobId) {
        if (jobId != null && jobId.toLowerCase(Locale.ROOT).startsWith("cgt-")) {
            log.warn("Agnes refuse to poll Ark jobId={}", jobId);
            return PollResult.failed(jobId,
                    "Seedance 官方模型不能走 Agnes，请使用适配协议 seedance-2.0 / seedance-2.5");
        }
        Job job = jobs.get(jobId);
        if (job == null) {
            return PollResult.failed(jobId, "Agnes 任务不存在");
        }
        if (isOfficialSeedance(job.request)) {
            return PollResult.failed(jobId, "Seedance 官方模型不能走 Agnes，请使用适配协议 seedance-2.0 / seedance-2.5");
        }
        if (job.outputKeys != null) {
            return PollResult.succeeded(jobId, job.outputKeys);
        }
        String mode = GenerateMode.canonical(safe(job.request.getMode()));
        if (GenerateMode.T2I.equalsIgnoreCase(mode) || GenerateMode.I2I.equalsIgnoreCase(mode)) {
            return PollResult.failed(jobId, "Agnes 图片任务无法恢复，请重新提交");
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
        log.info("Agnes cancel jobId={} (remote cancel not implemented)", jobId);
    }

    private SubmitResult submitImage(GenerateRequest request, String v1Base, String apiKey) {
        Map<String, Object> body = AgnesRequestMapper.toImageBody(request, mediaUris(request, false));
        JsonNode response = postJson(AgnesUrls.toUri(AgnesUrls.imagesGenerations(v1Base)), apiKey, body);
        JsonNode item = response.path("data").path(0);
        String url = item.path("url").asText("");
        String b64 = item.path("b64_json").asText("");
        if (!StringUtils.hasText(url) && !StringUtils.hasText(b64)) {
            throw new BizException(ErrorCode.INTERNAL, "Agnes 成功但未返回成片 URL");
        }
        String jobId = "img-" + UUID.randomUUID();
        Job job = new Job(request, v1Base, apiKey, AgnesRequestMapper.modelName(request), jobId);
        if (StringUtils.hasText(url)) {
            job.outputKeys = ingestUrl(request, url.trim(), false);
        } else {
            job.outputKeys = ingestBytes(request, decodeBase64(b64), ".png", false);
        }
        jobs.put(jobId, job);
        log.info("Agnes image submit jobId={}, taskId={}, model={}", jobId, request.getTaskId(), job.modelName);
        return SubmitResult.accepted(jobId);
    }

    private SubmitResult submitVideo(GenerateRequest request, String v1Base, String apiKey) {
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
            images = mediaUris(request, false);
        }
        Map<String, Object> body = AgnesRequestMapper.toVideoBody(request, first, last, images, List.of());
        JsonNode response = postJson(AgnesUrls.toUri(AgnesUrls.videos(v1Base)), apiKey, body);
        String taskId = AgnesResponses.taskId(response);
        String videoId = AgnesResponses.videoId(response);
        String status = AgnesResponses.status(response);
        if ("failed".equalsIgnoreCase(status)) {
            throw new BizException(ErrorCode.INTERNAL, firstNonBlank(AgnesErrors.message(response.toString()), "Agnes 提交失败"));
        }
        if (!StringUtils.hasText(videoId) && StringUtils.hasText(taskId)) {
            videoId = resolveVideoId(v1Base, apiKey, taskId);
        }
        String jobId = firstNonBlank(videoId, taskId);
        if (!StringUtils.hasText(jobId)) {
            throw new BizException(ErrorCode.INTERNAL, "Agnes 未返回 video_id");
        }
        Job job = new Job(request, v1Base, apiKey, AgnesRequestMapper.modelName(request), jobId);
        job.taskId = taskId;
        job.videoId = looksLikeVideoId(videoId) ? videoId : "";
        jobs.put(jobId, job);
        log.info("Agnes video submit jobId={}, videoId={}, taskId={}, status={}, model={}, mode={}",
                jobId, blankToDash(job.videoId), blankToDash(taskId), blankToDash(status),
                job.modelName, mode);
        if (!StringUtils.hasText(job.videoId)) {
            log.warn("Agnes create response missing video_id; will resolve before polling. taskId={} rawKeys={}",
                    taskId, fieldNames(response));
        }
        return SubmitResult.accepted(jobId);
    }

    private PollResult pollVideo(Job job, String jobId) {
        try {
            JsonNode response = queryVideo(job, jobId);
            if (response == null) {
                scheduleNextPoll(job, pollIntervalMs());
                return PollResult.running(jobId, Math.max(10, job.lastProgress));
            }
            rememberIds(job, response);
            String status = AgnesResponses.status(response);
            int progress = AgnesResponses.progress(response, job.lastProgress);
            job.lastProgress = Math.max(job.lastProgress, progress);
            job.consecutive429 = 0;
            if ("queued".equalsIgnoreCase(status)) {
                scheduleNextPoll(job, pollIntervalMs());
                return PollResult.pending(jobId, Math.max(10, progress));
            }
            if ("failed".equalsIgnoreCase(status)) {
                return PollResult.failed(jobId, firstNonBlank(AgnesErrors.message(response.toString()), "Agnes 生成失败"));
            }
            if (!"completed".equalsIgnoreCase(status)) {
                scheduleNextPoll(job, pollIntervalMs());
                return PollResult.running(jobId, Math.max(40, progress));
            }
            String url = AgnesResponses.outputUrl(response);
            if (!StringUtils.hasText(url)) {
                job.emptyUrlCount++;
                if (job.emptyUrlCount >= 20) {
                    log.warn("Agnes completed without output URL after {} polls jobId={} videoId={}",
                            job.emptyUrlCount, jobId, blankToDash(job.videoId));
                    return PollResult.failed(jobId, "Agnes 任务已完成但未返回成片地址");
                }
                log.info("Agnes completed but output URL not ready jobId={} videoId={} wait={}/20",
                        jobId, blankToDash(job.videoId), job.emptyUrlCount);
                scheduleNextPoll(job, pollIntervalMs());
                return PollResult.running(jobId, Math.max(90, progress));
            }
            job.emptyUrlCount = 0;
            job.outputKeys = ingestUrl(job.request, url.trim(), true);
            return PollResult.succeeded(jobId, job.outputKeys);
        } catch (BizException e) {
            if (rateLimited(e)) {
                long backoff = rateLimitBackoffMs(++job.consecutive429);
                scheduleNextPoll(job, backoff);
                log.warn("Agnes poll rate-limited jobId={} videoId={} backoffMs={}: {}",
                        jobId, blankToDash(job.videoId), backoff, e.getMessage());
                return PollResult.running(jobId, Math.max(40, job.lastProgress));
            }
            if (transientPollError(e)) {
                scheduleNextPoll(job, pollIntervalMs());
                log.warn("Agnes poll retryable jobId={}: {}", jobId, e.getMessage());
                return PollResult.running(jobId, Math.max(40, job.lastProgress));
            }
            log.warn("Agnes poll/ingest failed jobId={}: {}", jobId, e.getMessage());
            return PollResult.failed(jobId, e.getMessage());
        }
    }

    private JsonNode queryVideo(Job job, String jobId) {
        if (!looksLikeVideoId(job.videoId)) {
            String lookupId = firstNonBlank(job.taskId, jobId);
            JsonNode retrieved = retrieveQuietly(job, lookupId);
            if (retrieved != null) {
                rememberIds(job, retrieved);
                String url = AgnesResponses.outputUrl(retrieved);
                String status = AgnesResponses.status(retrieved);
                if (StringUtils.hasText(url) || "failed".equalsIgnoreCase(status)
                        || ("completed".equalsIgnoreCase(status) && looksLikeVideoId(job.videoId))) {
                    return retrieved;
                }
            }
        }
        String pollId = firstNonBlank(job.videoId, job.taskId, jobId);
        if (!StringUtils.hasText(pollId) || pollId.startsWith("img-")) {
            log.warn("Agnes skip status query until video_id is known jobId={} taskId={}",
                    jobId, blankToDash(job.taskId));
            return null;
        }
        if (!looksLikeVideoId(pollId)) {
            log.warn("Agnes status query using non-video id={} (official query requires video_id)", pollId);
        }
        return getJson(AgnesUrls.poll(job.v1Base, pollId, job.modelName), job.apiKey);
    }

    private String resolveVideoId(String v1Base, String apiKey, String taskId) {
        try {
            JsonNode retrieved = getJson(AgnesUrls.toUri(AgnesUrls.videoRetrieve(v1Base, taskId)), apiKey);
            return AgnesResponses.videoId(retrieved);
        } catch (BizException e) {
            log.warn("Agnes retrieve after create failed taskId={}: {}", taskId, e.getMessage());
            return "";
        }
    }

    private JsonNode retrieveQuietly(Job job, String lookupId) {
        if (!StringUtils.hasText(lookupId) || lookupId.startsWith("img-")) {
            return null;
        }
        try {
            return getJson(AgnesUrls.toUri(AgnesUrls.videoRetrieve(job.v1Base, lookupId)), job.apiKey);
        } catch (BizException e) {
            if (rateLimited(e)) {
                throw e;
            }
            log.warn("Agnes retrieve video failed jobId={}: {}", lookupId, e.getMessage());
            return null;
        }
    }

    private static void rememberIds(Job job, JsonNode response) {
        String videoId = AgnesResponses.videoId(response);
        if (looksLikeVideoId(videoId)) {
            job.videoId = videoId;
        }
        String taskId = AgnesResponses.taskId(response);
        if (StringUtils.hasText(taskId) && !looksLikeVideoId(taskId)) {
            job.taskId = taskId;
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

    private static boolean looksLikeVideoId(String value) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.ROOT).startsWith("video_");
    }

    private static String blankToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private static String fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "-";
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.isEmpty() ? "-" : String.join(",", names);
    }

    private void assertSupported(GenerateRequest request) {
        if ((request.getPrompt() == null || request.getPrompt().isBlank())
                && !GenerateMode.allowBlankPrompt(request.getFrames())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        if (isOfficialSeedance(request)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "Seedance 官方模型不能走 Agnes，请使用适配协议 seedance-2.0 / seedance-2.5");
        }
        String adapterKey = AdapterCatalog.resolveKey(
                request.getAdapterKey(), KEY, request.getCategory(), request.getModelKey());
        if (adapterKey != null
                && !AdapterCatalog.AGNES_IMAGE_2_5.equals(adapterKey)
                && !AdapterCatalog.AGNES_VIDEO_2_5.equals(adapterKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前官方 API 通道不支持该适配协议");
        }
        if (adapterKey != null && !AdapterCatalog.supportsMode(adapterKey, request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, AdapterCatalog.AGNES_IMAGE_2_5.equals(adapterKey)
                    ? "Agnes Image 2.5 仅支持文生图（t2i）与参考生图（i2i）"
                    : "Agnes Video 2.5 仅支持文生视频（t2v）、参考生视频（r2v）与首尾帧（i2v）");
        }
        String mode = GenerateMode.canonical(safe(request.getMode()));
        if (GenerateMode.T2I.equalsIgnoreCase(mode)) {
            if (request.getReferences() != null && !request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文生图请移除参考图，或改用支持 i2i 的模型");
            }
            return;
        }
        if (GenerateMode.I2I.equalsIgnoreCase(mode)) {
            if (request.getReferences() == null || request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生图至少需要一张图片");
            }
            assertImageRefs(request, Integer.MAX_VALUE);
            return;
        }
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
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频至少需要一张图片");
            }
            if (request.getFrames() != null && request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频请使用参考素材，不要同时提交首尾帧");
            }
            int imageLimit = AgnesRequestMapper.flashVideo(request)
                    ? AgnesRequestMapper.FLASH_MAX_IMAGES : Integer.MAX_VALUE;
            assertImageRefs(request, imageLimit);
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "官方 API 通道不支持该生成模式");
    }

    private void assertImageRefs(GenerateRequest request, int imageLimit) {
        int images = 0;
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            if (AssetKind.VIDEO.equalsIgnoreCase(ref.getType())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        GenerateMode.I2I.equalsIgnoreCase(GenerateMode.canonical(safe(request.getMode())))
                                ? "参考生图不支持视频素材，请改用图片"
                                : AgnesRequestMapper.flashVideo(request)
                                ? "Agnes Video 2.5 Flash 不支持参考视频，请改用参考图"
                                : "本期 Agnes 通道暂不支持参考视频，请改用参考图");
            }
            images++;
            if (images > imageLimit) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考图最多 " + imageLimit + " 张");
            }
        }
        if (images == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少需要一张参考图");
        }
    }

    private List<String> mediaUris(GenerateRequest request, boolean videos) {
        List<String> values = new ArrayList<>();
        if (request.getReferences() == null) {
            return values;
        }
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            boolean video = AssetKind.VIDEO.equalsIgnoreCase(ref.getType());
            if (video != videos) {
                continue;
            }
            values.add(mediaUri(ref.getStorageKey()));
        }
        return values;
    }

    private String mediaUri(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        if (!objectStorage.exists(storageKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材不存在：" + storageKey);
        }
        byte[] bytes = objectStorage.getBytes(storageKey);
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考素材文件为空");
        }
        String mime = contentTypeOf(storageKey);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private List<String> ingestUrl(GenerateRequest request, String url, boolean video) {
        log.info("Downloading Agnes output taskId={} host={}", request.getTaskId(), hostOf(url));
        byte[] bytes = http.getBytes(AgnesUrls.toUri(url));
        String ext = extensionOf(url, video);
        return ingestBytes(request, bytes, ext, video);
    }

    private List<String> ingestBytes(GenerateRequest request, byte[] bytes, String ext, boolean video) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.INTERNAL, "Agnes 成片为空");
        }
        long userId = request.getUserId() == null ? 0L : request.getUserId();
        long taskId = request.getTaskId() == null ? 0L : request.getTaskId();
        String key = "users/" + userId + "/outputs/" + taskId + "/1" + ext;
        objectStorage.put(key, bytes, contentType(ext, video));
        return List.of(key);
    }

    private JsonNode postJson(java.net.URI uri, String apiKey, Map<String, Object> body) {
        try {
            return readTree(http.postJson(uri, apiKey, objectMapper.writeValueAsString(body)));
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 Agnes 请求失败");
        }
    }

    private JsonNode getJson(java.net.URI uri, String apiKey) {
        return readTree(http.getJson(uri, apiKey));
    }

    private JsonNode readTree(String raw) {
        try {
            return objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "解析 Agnes 响应失败");
        }
    }

    private String resolveApiKey(GenerateRequest request) {
        if (StringUtils.hasText(request.getApiKey())) {
            return request.getApiKey().trim();
        }
        if (StringUtils.hasText(properties.getApiKey())) {
            return properties.getApiKey().trim();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "请在管理端填写 Agnes 官方 API Key");
    }

    private static boolean transientPollError(BizException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("429") || message.contains("超时") || message.contains("HTTP 5");
    }

    private static boolean rateLimited(BizException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("429") || message.toLowerCase(Locale.ROOT).contains("rate limit");
    }

    private static byte[] decodeBase64(String b64) {
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL, "Agnes Base64 成片无法解码");
        }
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
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".wav")) {
            return "audio/wav";
        }
        return "image/png";
    }

    private static String extensionOf(String url, boolean video) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains(".webm")) {
            return ".webm";
        }
        if (lower.contains(".jpg") || lower.contains(".jpeg")) {
            return ".jpg";
        }
        if (lower.contains(".webp")) {
            return ".webp";
        }
        if (lower.contains(".mp4") || video) {
            return video ? ".mp4" : ".png";
        }
        return video ? ".mp4" : ".png";
    }

    private static String contentType(String ext, boolean video) {
        return switch (ext) {
            case ".mp4" -> "video/mp4";
            case ".webm" -> "video/webm";
            case ".jpg" -> "image/jpeg";
            case ".webp" -> "image/webp";
            default -> video ? "video/mp4" : "image/png";
        };
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost() == null ? "unknown" : java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? "" : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : (second == null ? "" : second);
    }

    private static String firstNonBlank(String first, String second, String third) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        if (StringUtils.hasText(second)) {
            return second.trim();
        }
        return third == null ? "" : third.trim();
    }

    private static boolean isOfficialSeedance(GenerateRequest request) {
        if (request == null) {
            return false;
        }
        return AdapterCatalog.isArk(request.getAdapterKey())
                || AdapterCatalog.looksLikeOfficialSeedance(request.getModelKey());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Job {
        private final GenerateRequest request;
        private final String v1Base;
        private final String apiKey;
        private final String modelName;
        private volatile List<String> outputKeys;
        private volatile String videoId;
        private volatile String taskId;
        private volatile long nextPollAtMillis;
        private volatile int consecutive429;
        private volatile int emptyUrlCount;
        private volatile int lastProgress = 10;
        private volatile boolean pollInFlight;

        private Job(GenerateRequest request, String v1Base, String apiKey, String modelName, String jobId) {
            this.request = request;
            this.v1Base = v1Base;
            this.apiKey = apiKey;
            this.modelName = modelName;
            if (looksLikeVideoId(jobId)) {
                this.videoId = jobId;
            } else if (StringUtils.hasText(jobId) && !jobId.startsWith("img-")) {
                this.taskId = jobId;
            }
        }
    }
}
