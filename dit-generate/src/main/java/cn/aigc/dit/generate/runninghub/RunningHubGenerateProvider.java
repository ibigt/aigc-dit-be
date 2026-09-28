package cn.aigc.dit.generate.runninghub;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RunningHub 运输层：submit/query/download。文生图/图生图走 gpt-image-2，视频走 seedance-2。
 * 当前实现文生图（t2i）、图生图（i2i）、文生视频（t2v）与参考生视频（r2v）；首尾帧（i2v）未接。
 */
@Slf4j
@Component
public class RunningHubGenerateProvider implements GenerateProvider {

    public static final String KEY = "runninghub";

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Runninghub properties;
    private final RunningHubHttp http;
    private final ObjectMapper objectMapper;
    private final AtomicInteger callCount = new AtomicInteger();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Autowired
    public RunningHubGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkRunningHubHttp(properties.getRunninghub()), new ObjectMapper());
    }

    RunningHubGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                               RunningHubHttp http, ObjectMapper objectMapper) {
        this.objectStorage = objectStorage;
        this.properties = properties.getRunninghub();
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
        String path = submitPath(request);
        String openApiBase = RunningHubUrls.openApiBase(request.getEndpoint(), properties.getBaseUrl());
        Map<String, Object> body = submitBody(request, path, apiKey, openApiBase);
        synchronized (callCount) {
            int limit = properties.getMaxCalls();
            if (limit > 0 && callCount.get() >= limit) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "RunningHub 验证调用次数已达上限（" + limit + "），已拒绝以免超额");
            }
            RunningHubTaskResponse response = post(RunningHubUrls.submitUrl(openApiBase, path), apiKey, body);
            String jobId = requireJobId(response);
            callCount.incrementAndGet();
            jobs.put(jobId, new Job(request, openApiBase, apiKey));
            log.info("RunningHub submit jobId={}, taskId={}, path={}, calls={}/{}",
                    jobId, request.getTaskId(), path, callCount.get(), limit > 0 ? limit : "unlimited");
            if (isSuccess(response) && hasResultUrl(response)) {
                jobs.get(jobId).pendingResults = response.getResults();
            }
            return SubmitResult.accepted(jobId);
        }
    }

    public void remember(String jobId, GenerateRequest request, String openApiBase, String apiKey) {
        if (!StringUtils.hasText(jobId) || request == null) {
            return;
        }
        jobs.computeIfAbsent(jobId, id -> new Job(request, openApiBase, apiKey));
    }

    /**
     * 用一个不存在的任务号查询：HTTP 成功即鉴权通过；401/403 = Key 无效；其他 HTTP 错误（任务不存在等）= 通过。
     * 整体无副作用、不消耗 RunningHub 调用额度。
     */
    @Override
    public void ping(GenerateRequest request) {
        String apiKey = firstNonBlank(request.getApiKey(), properties.getApiKey());
        if (apiKey == null) {
            throw new cn.aigc.dit.common.exception.BizException(
                    cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST, "RunningHub 测试连接需要 API Key");
        }
        String openApiBase = RunningHubUrls.openApiBase(request.getEndpoint(), properties.getBaseUrl());
        String url = RunningHubUrls.queryUrl(openApiBase);
        try {
            http.postJson(URI.create(url), apiKey,
                    "{\"taskId\":\"" + cn.aigc.dit.generate.PingSupport.PROBE_TASK_ID + "\"}");
        } catch (cn.aigc.dit.common.exception.BizException e) {
            // HTTP 层异常：401/403 = Key 无效；其他状态（含 200 但鉴权错误码）= 链路与鉴权通过
            Integer status = cn.aigc.dit.generate.PingSupport.httpStatusOf(e);
            if (status == null || cn.aigc.dit.generate.PingSupport.isUnauthorized(status)) {
                throw e;
            }
        }
    }

    @Override
    public PollResult poll(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            return PollResult.failed(jobId, "RunningHub 任务不存在");
        }
        try {
            RunningHubTaskResponse response = job.pendingResults != null
                    ? syntheticSuccess(jobId, job.pendingResults)
                    : post(RunningHubUrls.queryUrl(job.openApiBase), job.apiKey, Map.of("taskId", jobId));
            String status = response.getStatus() == null ? "" : response.getStatus().toUpperCase(Locale.ROOT);
            if ("QUEUED".equals(status)) {
                return PollResult.pending(jobId, 15);
            }
            if ("RUNNING".equals(status) || status.isBlank()) {
                return PollResult.running(jobId, 55);
            }
            if ("FAILED".equals(status) || "CANCELLED".equals(status) || "CANCELED".equals(status)) {
                return PollResult.failed(jobId, firstNonBlank(response.getErrorMessage(), "RunningHub 生成失败"));
            }
            if (!"SUCCESS".equals(status)) {
                return PollResult.running(jobId, 40);
            }
            if (job.outputKeys == null) {
                job.outputKeys = ingest(job.request, response);
                job.pendingResults = null;
            }
            return PollResult.succeeded(jobId, job.outputKeys);
        } catch (BizException e) {
            log.warn("RunningHub poll/ingest failed jobId={}: {}", jobId, e.getMessage());
            return PollResult.failed(jobId, e.getMessage());
        }
    }

    @Override
    public void cancel(String jobId) {
        jobs.remove(jobId);
        log.info("RunningHub cancel jobId={} (remote cancel not supported in OpenAPI v2 query)", jobId);
    }

    int callCount() {
        return callCount.get();
    }

    private void assertSupported(GenerateRequest request) {
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        String adapterKey = AdapterCatalog.resolveKey(
                request.getAdapterKey(), RunningHubGenerateProvider.KEY, request.getCategory(), request.getModelKey());
        if (adapterKey != null
                && !AdapterCatalog.GPT_IMAGE_2.equals(adapterKey)
                && !AdapterCatalog.SEEDANCE_2.equals(adapterKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前 RunningHub 通道不支持该适配协议");
        }
        if (adapterKey != null && !AdapterCatalog.supportsMode(adapterKey, request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    AdapterCatalog.GPT_IMAGE_2.equals(adapterKey)
                            ? "GPT Image 2 仅支持文生图（t2i）与图生图（i2i）"
                            : "Seedance 2.x 仅支持文生视频（t2v）与参考生视频（r2v）");
        }
        String mode = GenerateMode.canonical(safe(request.getMode()));
        if (GenerateMode.T2I.equalsIgnoreCase(mode)) {
            if (request.getReferences() != null && !request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文生图请移除参考图，或改用支持 i2i 的模型");
            }
            if (request.getFrames() != null && request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "gpt-image-2 不支持首尾帧（i2v）");
            }
            return;
        }
        if (GenerateMode.I2I.equalsIgnoreCase(mode)) {
            if (request.getReferences() == null || request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图生图至少需要一张参考图");
            }
            if (request.getFrames() != null && request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图生图请使用参考素材，不要同时提交首尾帧");
            }
            for (ReferenceAsset ref : request.getReferences()) {
                if (ref != null && AssetKind.VIDEO.equalsIgnoreCase(ref.getType())) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "图生图仅支持图片参考，不支持视频");
                }
            }
            return;
        }
        if (GenerateMode.T2V.equalsIgnoreCase(mode)) {
            if (request.getReferences() != null && !request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "当前通道为文生视频（t2v），请改用支持 r2v 的模型或移除参考素材");
            }
            if (request.getFrames() != null && request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "本期 RunningHub Adapter 不支持首尾帧（i2v）");
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
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST,
                "本期 RunningHub Adapter 仅支持文生图（t2i）、图生图（i2i）、文生视频（t2v）与参考生视频（r2v）");
    }

    private String submitPath(GenerateRequest request) {
        String mode = GenerateMode.canonical(safe(request.getMode()));
        if (GenerateMode.T2V.equalsIgnoreCase(mode)) {
            return RunningHubRequestMapper.t2vPath(request, properties.getT2vPath());
        }
        if (GenerateMode.R2V.equalsIgnoreCase(mode)) {
            return RunningHubRequestMapper.r2vPath(request, properties.getR2vPath());
        }
        if (GenerateMode.I2I.equalsIgnoreCase(mode)) {
            return RunningHubRequestMapper.i2iPath(request, properties.getI2iPath());
        }
        return RunningHubRequestMapper.t2iPath(request, properties.getT2iPath());
    }

    private Map<String, Object> submitBody(GenerateRequest request, String path, String apiKey, String openApiBase) {
        String mode = GenerateMode.canonical(safe(request.getMode()));
        if (GenerateMode.T2V.equalsIgnoreCase(mode)) {
            return RunningHubRequestMapper.toT2vBody(request);
        }
        if (GenerateMode.R2V.equalsIgnoreCase(mode)) {
            return RunningHubRequestMapper.toR2vBody(request,
                    uploadReferences(request, apiKey, openApiBase, false),
                    uploadReferences(request, apiKey, openApiBase, true));
        }
        if (GenerateMode.I2I.equalsIgnoreCase(mode)) {
            return RunningHubRequestMapper.toI2iBody(
                    request, path, uploadI2iImages(request, apiKey, openApiBase),
                    properties.getForceResolution(), properties.getForceQuality());
        }
        return RunningHubRequestMapper.toT2iBody(
                request, path, properties.getForceResolution(), properties.getForceQuality());
    }

    private List<String> uploadI2iImages(GenerateRequest request, String apiKey, String openApiBase) {
        List<String> urls = new ArrayList<>();
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            if (AssetKind.VIDEO.equalsIgnoreCase(ref.getType())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图生图仅支持图片参考，不支持视频");
            }
            if (urls.size() >= RunningHubRequestMapper.MAX_I2I_IMAGES) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图生图参考图最多 " + RunningHubRequestMapper.MAX_I2I_IMAGES + " 张");
            }
            urls.add(uploadReference(ref, apiKey, openApiBase));
        }
        if (urls.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图生图至少需要一张参考图");
        }
        return urls;
    }

    private List<String> uploadReferences(GenerateRequest request, String apiKey, String openApiBase, boolean videos) {
        List<String> urls = new ArrayList<>();
        int limit = videos ? 3 : 9;
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            boolean video = AssetKind.VIDEO.equalsIgnoreCase(ref.getType());
            if (video != videos) {
                continue;
            }
            if (urls.size() >= limit) {
                throw new BizException(ErrorCode.BAD_REQUEST, videos ? "参考视频最多 3 段" : "参考图最多 9 张");
            }
            urls.add(uploadReference(ref, apiKey, openApiBase));
        }
        return urls;
    }

    private String uploadReference(ReferenceAsset ref, String apiKey, String openApiBase) {
        byte[] bytes = objectStorage.getBytes(ref.getStorageKey());
        String filename = filenameOf(ref.getStorageKey());
        String raw = http.uploadBinary(
                URI.create(RunningHubUrls.uploadUrl(openApiBase)),
                apiKey,
                filename,
                contentTypeOf(filename, ref.getType()),
                bytes);
        String url = parseDownloadUrl(raw);
        if (!StringUtils.hasText(url)) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 上传参考素材未返回 download_url");
        }
        return url.trim();
    }

    private String parseDownloadUrl(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("code")) {
                int code = root.get("code").asInt(-1);
                if (code != 0 && code != 200) {
                    throw new BizException(ErrorCode.INTERNAL, firstNonBlank(
                            root.path("message").asText(null), "RunningHub 上传参考素材失败"));
                }
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            String url = firstNonBlank(text(data, "download_url"), text(data, "downloadUrl"));
            return url;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 上传应答无法解析");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText();
        return StringUtils.hasText(value) ? value : null;
    }

    private static String filenameOf(String storageKey) {
        String name = storageKey.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return StringUtils.hasText(name) ? name.replace("\"", "_") : "ref.bin";
    }

    private static String contentTypeOf(String filename, String type) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        return AssetKind.VIDEO.equalsIgnoreCase(type) ? "video/mp4" : "image/png";
    }

    private String resolveApiKey(GenerateRequest request) {
        if (StringUtils.hasText(request.getApiKey())) {
            return request.getApiKey().trim();
        }
        if (StringUtils.hasText(properties.getApiKey())) {
            return properties.getApiKey().trim();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "未配置 RunningHub API Key（环境变量 RUNNINGHUB_API_KEY 或模型密钥）");
    }

    private RunningHubTaskResponse post(String url, String apiKey, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 请求序列化失败");
        }
        String raw = http.postJson(URI.create(url), apiKey, json);
        try {
            RunningHubTaskResponse response = parseTaskResponse(raw);
            if (response == null) {
                throw new BizException(ErrorCode.INTERNAL, "RunningHub 应答为空");
            }
            return response;
        } catch (BizException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 应答无法解析");
        }
    }

    private RunningHubTaskResponse parseTaskResponse(String raw) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(raw);
        if (root.has("code")) {
            int code = root.get("code").asInt(-1);
            if (code != 0 && code != 200) {
                throw new BizException(ErrorCode.INTERNAL, firstNonBlank(
                        root.path("message").asText(null), "RunningHub 查询失败"));
            }
        }
        JsonNode payload = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        return objectMapper.treeToValue(payload, RunningHubTaskResponse.class);
    }

    private static URI toUri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 成片 URL 无效");
        }
    }

    private static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "unknown" : uri.getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String requireJobId(RunningHubTaskResponse response) {
        if (response == null || !StringUtils.hasText(response.getTaskId())) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 未返回 taskId");
        }
        if (isFailed(response)) {
            throw new BizException(ErrorCode.INTERNAL, firstNonBlank(response.getErrorMessage(), "RunningHub 提交失败"));
        }
        return response.getTaskId().trim();
    }

    private List<String> ingest(GenerateRequest request, RunningHubTaskResponse response) {
        List<RunningHubTaskResponse.ResultItem> items = ingestItems(request, response);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 成功但未返回成片 URL");
        }
        List<String> keys = new ArrayList<>();
        long userId = request.getUserId() == null ? 0L : request.getUserId();
        long taskId = request.getTaskId() == null ? 0L : request.getTaskId();
        int index = 1;
        for (RunningHubTaskResponse.ResultItem item : items) {
            if (item == null || !StringUtils.hasText(item.getUrl())) {
                continue;
            }
            String url = item.getUrl().trim();
            log.info("Downloading RunningHub output taskId={} host={}", taskId, hostOf(url));
            byte[] bytes = http.getBytes(toUri(url));
            String ext = extension(item);
            String key = "users/" + userId + "/outputs/" + taskId + "/" + index + ext;
            objectStorage.put(key, bytes, contentType(ext));
            keys.add(key);
            index++;
        }
        if (keys.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL, "RunningHub 成片回收失败");
        }
        return keys;
    }

    private static List<RunningHubTaskResponse.ResultItem> ingestItems(
            GenerateRequest request, RunningHubTaskResponse response) {
        if (response.getResults() == null) {
            return List.of();
        }
        List<RunningHubTaskResponse.ResultItem> all = response.getResults().stream()
                .filter(item -> item != null && StringUtils.hasText(item.getUrl()))
                .toList();
        if (GenerateMode.isVideo(safe(request.getMode()))) {
            List<RunningHubTaskResponse.ResultItem> videos = all.stream()
                    .filter(RunningHubGenerateProvider::isVideoResult)
                    .toList();
            if (!videos.isEmpty()) {
                return videos;
            }
        }
        return all;
    }

    private RunningHubTaskResponse syntheticSuccess(String jobId, List<RunningHubTaskResponse.ResultItem> results) {
        RunningHubTaskResponse response = new RunningHubTaskResponse();
        response.setTaskId(jobId);
        response.setStatus("SUCCESS");
        response.setResults(results);
        return response;
    }

    private static boolean hasResultUrl(RunningHubTaskResponse response) {
        if (response.getResults() == null) {
            return false;
        }
        return response.getResults().stream().anyMatch(item -> item != null && StringUtils.hasText(item.getUrl()));
    }

    private static boolean isSuccess(RunningHubTaskResponse response) {
        return response.getStatus() != null && "SUCCESS".equalsIgnoreCase(response.getStatus());
    }

    private static boolean isFailed(RunningHubTaskResponse response) {
        return response.getStatus() != null && "FAILED".equalsIgnoreCase(response.getStatus());
    }

    private static String extension(RunningHubTaskResponse.ResultItem item) {
        String type = item.getOutputType() == null ? "" : item.getOutputType().toLowerCase(Locale.ROOT);
        if (type.contains("mp4")) {
            return ".mp4";
        }
        if (type.contains("webm")) {
            return ".webm";
        }
        if (type.contains("jpg") || type.contains("jpeg")) {
            return ".jpg";
        }
        if (type.contains("webp")) {
            return ".webp";
        }
        if (type.contains("png")) {
            return ".png";
        }
        String url = item.getUrl().toLowerCase(Locale.ROOT);
        if (url.contains(".mp4")) {
            return ".mp4";
        }
        if (url.contains(".webm")) {
            return ".webm";
        }
        if (url.contains(".jpg") || url.contains(".jpeg")) {
            return ".jpg";
        }
        if (url.contains(".webp")) {
            return ".webp";
        }
        return ".png";
    }

    private static String contentType(String ext) {
        return switch (ext) {
            case ".mp4" -> "video/mp4";
            case ".webm" -> "video/webm";
            case ".jpg" -> "image/jpeg";
            case ".webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private static boolean isVideoResult(RunningHubTaskResponse.ResultItem item) {
        String type = item.getOutputType() == null ? "" : item.getOutputType().toLowerCase(Locale.ROOT);
        String url = item.getUrl() == null ? "" : item.getUrl().toLowerCase(Locale.ROOT);
        return type.contains("mp4") || type.contains("webm") || type.contains("video")
                || url.contains(".mp4") || url.contains(".webm");
    }

    private static String firstNonBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Job {
        private final GenerateRequest request;
        private final String openApiBase;
        private final String apiKey;
        private volatile List<RunningHubTaskResponse.ResultItem> pendingResults;
        private volatile List<String> outputKeys;

        private Job(GenerateRequest request, String openApiBase, String apiKey) {
            this.request = request;
            this.openApiBase = openApiBase;
            this.apiKey = apiKey;
        }
    }
}
