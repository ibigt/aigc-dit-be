package cn.aigc.dit.generate.comfyui;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ComfyUI 运输层：submit/poll/download。MiniMax-H3 工作流由适配协议 {@code minimax-h3} 翻译。
 */
@Slf4j
@Component
public class ComfyUiGenerateProvider implements GenerateProvider {

    public static final String KEY = "comfyui";

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Comfyui properties;
    private final ComfyUiHttp http;
    private final ObjectMapper objectMapper;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Autowired
    public ComfyUiGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkComfyUiHttp(properties.getComfyui()), new ObjectMapper());
    }

    ComfyUiGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                            ComfyUiHttp http, ObjectMapper objectMapper) {
        this.objectStorage = objectStorage;
        this.properties = properties.getComfyui();
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
        String base = ComfyUiUrls.base(request.getEndpoint(), properties.getBaseUrl());
        String apiKey = request.getApiKey();
        Map<String, Object> workflow = buildWorkflow(request, base, apiKey);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", workflow);
        payload.put("client_id", "dit-" + UUID.randomUUID());
        JsonNode response = postJson(ComfyUiUrls.prompt(base), apiKey, payload);
        assertNoNodeErrors(response);
        String jobId = text(response, "prompt_id");
        if (!StringUtils.hasText(jobId)) {
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 未返回 prompt_id");
        }
        jobs.put(jobId, new Job(request, base, apiKey));
        log.info("ComfyUI submit jobId={}, taskId={}, mode={}, base={}",
                jobId, request.getTaskId(), request.getMode(), base);
        return SubmitResult.accepted(jobId);
    }

    public void remember(String jobId, GenerateRequest request, String endpoint) {
        if (!StringUtils.hasText(jobId) || request == null) {
            return;
        }
        String base = ComfyUiUrls.base(endpoint, properties.getBaseUrl());
        jobs.computeIfAbsent(jobId, id -> new Job(request, base, request.getApiKey()));
    }

    @Override
    public PollResult poll(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            return PollResult.failed(jobId, "ComfyUI 任务不存在");
        }
        try {
            JsonNode history = getJson(ComfyUiUrls.history(job.base, jobId), job.apiKey);
            JsonNode entry = history.path(jobId);
            if (entry.isMissingNode() || !entry.isObject()) {
                return PollResult.running(jobId, 40);
            }
            String status = entry.path("status").path("status_str").asText("");
            if ("error".equalsIgnoreCase(status)) {
                return PollResult.failed(jobId, firstNonBlank(errorMessage(entry), "ComfyUI 生成失败"));
            }
            boolean completed = entry.path("status").path("completed").asBoolean(false);
            if (!completed && !"success".equalsIgnoreCase(status)) {
                return PollResult.running(jobId, 70);
            }
            if (job.outputKeys == null) {
                job.outputKeys = ingest(job, entry);
            }
            return PollResult.succeeded(jobId, job.outputKeys);
        } catch (BizException e) {
            if (isRetryablePollError(e.getMessage())) {
                log.warn("ComfyUI poll busy jobId={}, will retry: {}", jobId, e.getMessage());
                return PollResult.running(jobId, 40);
            }
            log.warn("ComfyUI poll/ingest failed jobId={}: {}", jobId, e.getMessage());
            return PollResult.failed(jobId, e.getMessage());
        }
    }

    @Override
    public void cancel(String jobId) {
        Job job = jobs.remove(jobId);
        if (job != null) {
            try {
                http.postJson(ComfyUiUrls.interrupt(job.base), job.apiKey, "{}");
            } catch (RuntimeException e) {
                log.warn("ComfyUI interrupt failed jobId={}: {}", jobId, e.getMessage());
            }
        }
        log.info("ComfyUI cancel jobId={}", jobId);
    }

    static boolean isRetryablePollError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return message.contains("请求超时")
                || message.contains("请求失败")
                || message.contains("请求被中断")
                || message.contains("下载 ComfyUI 成片超时")
                || message.contains("下载 ComfyUI 成片失败")
                || lower.contains("http 502")
                || lower.contains("http 503")
                || lower.contains("http 504");
    }

    private void assertSupported(GenerateRequest request) {
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        String adapterKey = AdapterCatalog.resolveKey(
                request.getAdapterKey(), null, request.getCategory(), request.getModelKey());
        if (adapterKey != null && !AdapterCatalog.MINIMAX_H3.equals(adapterKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前 ComfyUI 通道仅支持适配协议 MiniMax-H3");
        }
        if (adapterKey != null && !AdapterCatalog.supportsMode(adapterKey, request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前适配协议不支持该生成模式");
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
        if (GenerateMode.R2V.equalsIgnoreCase(mode)) {
            if (request.getReferences() == null || request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频至少需要一张图片或一段视频");
            }
            if (request.getFrames() != null && request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频请使用参考素材，不要同时提交首尾帧");
            }
            return;
        }
        if (GenerateMode.I2V.equalsIgnoreCase(mode)) {
            if (request.getFrames() == null || !request.getFrames().hasAny()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "首尾帧模式需要上传首帧或尾帧");
            }
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "当前 ComfyUI MiniMax-H3 仅支持文生视频（t2v）、参考生视频（r2v）与首尾帧（i2v）");
    }

    private Map<String, Object> buildWorkflow(GenerateRequest request, String base, String apiKey) {
        String mode = GenerateMode.canonical(safe(request.getMode()));
        int[] size = ComfyUiGeometry.size(request.getRatio(), request.getResolution());
        int length = ComfyUiGeometry.frameCount(request.getDurationSec());
        int seed = request.getTaskId() == null ? 42 : request.getTaskId().intValue();
        if (GenerateMode.R2V.equalsIgnoreCase(mode)) {
            List<String> images = new ArrayList<>();
            List<String> videos = new ArrayList<>();
            for (ReferenceAsset ref : request.getReferences()) {
                if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                    continue;
                }
                boolean video = AssetKind.VIDEO.equalsIgnoreCase(ref.getType());
                if (video) {
                    if (videos.size() >= 3) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "参考视频最多 3 段");
                    }
                    videos.add(upload(base, apiKey, ref.getStorageKey(), ref.getType()));
                } else {
                    if (images.size() >= 9) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "参考图最多 9 张");
                    }
                    images.add(upload(base, apiKey, ref.getStorageKey(), ref.getType()));
                }
            }
            if (images.isEmpty() && videos.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生视频至少需要一张图片或一段视频");
            }
            return ComfyUiMinimaxGraph.referenceToVideo(
                    r2vPrompt(request.getPrompt(), images.size(), videos.size()),
                    size[0], size[1], length, seed,
                    config(request, "unetName", ComfyUiMinimaxGraph.UNET_R2V),
                    config(request, "loraName", ComfyUiMinimaxGraph.LORA_R2V),
                    configInt(request, "steps", ComfyUiMinimaxGraph.R2V_STEPS),
                    images, videos);
        }
        String first = null;
        String last = null;
        if (GenerateMode.I2V.equalsIgnoreCase(mode)) {
            FramePair frames = request.getFrames();
            if (StringUtils.hasText(frames.getFirstFrameKey())) {
                first = upload(base, apiKey, frames.getFirstFrameKey(), AssetKind.IMAGE);
            }
            if (StringUtils.hasText(frames.getLastFrameKey())) {
                last = upload(base, apiKey, frames.getLastFrameKey(), AssetKind.IMAGE);
            }
        }
        return ComfyUiMinimaxGraph.imageToVideo(
                request.getPrompt().trim(),
                size[0], size[1], length, seed,
                config(request, "unetName", ComfyUiMinimaxGraph.UNET_T2V),
                config(request, "loraName", ComfyUiMinimaxGraph.LORA_T2V),
                configInt(request, "steps", ComfyUiMinimaxGraph.T2V_STEPS),
                first, last);
    }

    private String upload(String base, String apiKey, String storageKey, String type) {
        byte[] bytes = objectStorage.getBytes(storageKey);
        String filename = filenameOf(storageKey);
        String raw = http.uploadImage(ComfyUiUrls.uploadImage(base), apiKey, filename, contentTypeOf(filename, type), bytes);
        try {
            JsonNode root = objectMapper.readTree(raw);
            String name = firstNonBlank(text(root, "name"), filename);
            String subfolder = text(root, "subfolder");
            return StringUtils.hasText(subfolder) ? subfolder + "/" + name : name;
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 上传应答无法解析");
        }
    }

    private List<String> ingest(Job job, JsonNode entry) {
        List<OutputFile> files = outputFiles(entry.path("outputs"));
        if (files.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 成功但未返回成片");
        }
        List<String> keys = new ArrayList<>();
        long userId = job.request.getUserId() == null ? 0L : job.request.getUserId();
        long taskId = job.request.getTaskId() == null ? 0L : job.request.getTaskId();
        int index = 1;
        for (OutputFile file : files) {
            byte[] bytes = http.getBytes(
                    ComfyUiUrls.view(job.base, file.filename, file.subfolder, file.type), job.apiKey);
            String ext = extension(file.filename);
            String key = "users/" + userId + "/outputs/" + taskId + "/" + index + ext;
            objectStorage.put(key, bytes, contentType(ext));
            keys.add(key);
            index++;
        }
        return keys;
    }

    private static List<OutputFile> outputFiles(JsonNode outputs) {
        List<OutputFile> videos = new ArrayList<>();
        List<OutputFile> others = new ArrayList<>();
        if (outputs == null || !outputs.isObject()) {
            return videos;
        }
        outputs.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            collect(node.path("videos"), videos, others);
            collect(node.path("gifs"), videos, others);
            collect(node.path("images"), videos, others);
        });
        return videos.isEmpty() ? others : videos;
    }

    private static void collect(JsonNode array, List<OutputFile> videos, List<OutputFile> others) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String filename = text(item, "filename");
            if (!StringUtils.hasText(filename)) {
                continue;
            }
            OutputFile file = new OutputFile(filename, text(item, "subfolder"), text(item, "type"));
            if (filename.toLowerCase(Locale.ROOT).endsWith(".mp4")
                    || filename.toLowerCase(Locale.ROOT).endsWith(".webm")) {
                videos.add(file);
            } else {
                others.add(file);
            }
        }
    }

    private JsonNode postJson(java.net.URI uri, String apiKey, Object payload) {
        try {
            return objectMapper.readTree(http.postJson(uri, apiKey, objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 请求序列化失败");
        }
    }

    private JsonNode getJson(java.net.URI uri, String apiKey) {
        try {
            String raw = http.getJson(uri, apiKey);
            return objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 应答无法解析");
        }
    }

    private static void assertNoNodeErrors(JsonNode response) {
        JsonNode errors = response.path("node_errors");
        if (errors.isObject() && errors.size() > 0) {
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 工作流校验失败: " + errors);
        }
        if (response.hasNonNull("error")) {
            JsonNode error = response.get("error");
            String message = error.isTextual() ? error.asText() : error.toString();
            throw new BizException(ErrorCode.INTERNAL, "ComfyUI 提交失败: " + message);
        }
    }

    private static String errorMessage(JsonNode entry) {
        JsonNode messages = entry.path("status").path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if (message.isArray() && message.size() > 1
                        && "execution_error".equals(message.get(0).asText())) {
                    return firstNonBlank(
                            text(message.get(1), "exception_message"),
                            text(message.get(1), "exception_type"));
                }
            }
        }
        return null;
    }

    private static String r2vPrompt(String prompt, int imageCount, int videoCount) {
        String text = prompt == null ? "" : prompt.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<picture") || lower.contains("@image") || lower.contains("@video")) {
            return text;
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 1; i <= imageCount; i++) {
            if (!prefix.isEmpty()) {
                prefix.append(' ');
            }
            prefix.append("<Picture ").append(i).append('>');
        }
        for (int i = 1; i <= videoCount; i++) {
            if (!prefix.isEmpty()) {
                prefix.append(' ');
            }
            prefix.append("<Video ").append(i).append('>');
        }
        if (prefix.isEmpty()) {
            return text;
        }
        return text.isBlank() ? prefix.toString() : prefix + " " + text;
    }

    private static String config(GenerateRequest request, String key, String fallback) {
        Object value = request.getAdapterConfig() == null ? null : request.getAdapterConfig().get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value).trim();
    }

    private static int configInt(GenerateRequest request, String key, int fallback) {
        Object value = request.getAdapterConfig() == null ? null : request.getAdapterConfig().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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
        return AssetKind.VIDEO.equalsIgnoreCase(type) ? "video/mp4" : "image/png";
    }

    private static String extension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot > 0) {
            return lower.substring(dot);
        }
        return ".mp4";
    }

    private static String contentType(String ext) {
        if (".png".equals(ext) || ".jpg".equals(ext) || ".jpeg".equals(ext) || ".webp".equals(ext)) {
            return "image/" + ext.substring(1);
        }
        return "video/mp4";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText();
        return StringUtils.hasText(value) ? value : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Job {
        private final GenerateRequest request;
        private final String base;
        private final String apiKey;
        private volatile List<String> outputKeys;

        private Job(GenerateRequest request, String base, String apiKey) {
            this.request = request;
            this.base = base;
            this.apiKey = apiKey;
        }
    }

    private record OutputFile(String filename, String subfolder, String type) {
    }
}
