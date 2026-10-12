package cn.aigc.dit.generate.sglang;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.*;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** SGLang Diffusion asynchronous video API, including authenticated MP4 download. */
@Component
public class SglangGenerateProvider implements GenerateProvider {
    private final ObjectStorage storage;
    private final SglangHttp http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Autowired
    public SglangGenerateProvider(ObjectStorage storage) {
        this(storage, new JdkSglangHttp());
    }

    SglangGenerateProvider(ObjectStorage storage, SglangHttp http) {
        this.storage = storage;
        this.http = http;
    }

    @Override public String providerKey() { return "sglang"; }

    static String base(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw bad("请配置 SGLang 服务地址");
        String base = endpoint.trim().replaceAll("/+$", "");
        if (base.endsWith("/videos")) base = base.substring(0, base.length() - 7);
        if (!base.endsWith("/v1")) base += "/v1";
        URI uri = URI.create(base);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getQuery() != null || uri.getFragment() != null) throw bad("SGLang 地址必须为 HTTP Base URL");
        return base;
    }

    @Override public SubmitResult submit(GenerateRequest request) {
        String base = base(request.getEndpoint());
        Map<String, Object> body = createBody(request);
        JsonNode response = json("POST", base + "/videos", request.getApiKey(), body);
        String id = response.path("id").asText("");
        if (id.isBlank()) throw new BizException(ErrorCode.INTERNAL, "SGLang 未返回任务 ID");
        if ("failed".equals(response.path("status").asText())) throw bad(error(response));
        remember(id, request);
        return SubmitResult.accepted(id);
    }

    public void remember(String id, GenerateRequest request) {
        if (id != null && !id.isBlank() && request != null)
            jobs.computeIfAbsent(id, ignored -> new Job(request, base(request.getEndpoint())));
    }

    Map<String, Object> createBody(GenerateRequest request) {
        if (request.resolvedCopies() != 1) throw bad("SGLang 当前每次任务仅支持一个视频，请分别提交");
        String mode = GenerateMode.canonical(request.getMode());
        if (!AdapterCatalog.supportsMode(AdapterCatalog.MINIMAX_H3_SGLANG, mode)) throw bad("SGLang 不支持该模式");
        if (request.getAdapterKey() != null && !AdapterCatalog.MINIMAX_H3_SGLANG.equals(request.getAdapterKey()))
            throw bad("SGLang 不支持该适配协议");
        List<Map<String, Object>> conditions = new ArrayList<>();
        FramePair frames = request.getFrames();
        boolean hasFrames = frames != null && frames.hasAny();
        List<ReferenceAsset> refs = request.getReferences();
        boolean hasRefs = refs != null && !refs.isEmpty();
        String task;
        if (GenerateMode.T2V.equals(mode)) {
            if (hasFrames || hasRefs) throw bad("文生视频不能提交首尾帧或参考素材");
            task = "t2va";
        } else if (GenerateMode.I2V.equals(mode)) {
            if (!hasFrames || hasRefs) throw bad("首尾帧模式需要帧图片，不能混入参考素材");
            task = "fl2va";
            addFrame(conditions, frames.getFirstFrameKey(), 0);
            addFrame(conditions, frames.getLastFrameKey(), -1);
        } else {
            if (!hasRefs || hasFrames) throw bad("参考模式需要参考素材，不能混入首尾帧");
            task = "ref2va";
            int audioCount = 0;
            for (ReferenceAsset ref : refs) {
                if (ref == null || ref.getStorageKey() == null || ref.getStorageKey().isBlank()) throw bad("参考素材不能为空");
                String type = ref.getType() == null ? "image" : ref.getType().toLowerCase(Locale.ROOT);
                type = type.startsWith("video") ? "video" : type.startsWith("audio") ? "audio" : "image";
                if ("audio".equals(type) && ++audioCount > 3) throw bad("参考音频最多 3 段");
                conditions.add(Map.of("type", type, "uri", mediaUri(ref.getStorageKey()), "role", "reference"));
            }
        }
        String prompt = request.getPrompt();
        if ((prompt == null || prompt.isBlank()) && !hasFrames) throw bad("提示词不能为空");
        int seconds = request.getDurationSec() == null ? 5 : request.getDurationSec();
        if (seconds < 4 || seconds > 15) throw bad("SGLang MiniMax-H3 时长必须为 4–15 秒");
        String ratio = request.getRatio() == null || request.getRatio().isBlank() ? "16:9" : request.getRatio();
        ratio = ratio.replace('：', ':');
        if ("adaptive".equalsIgnoreCase(ratio)) ratio = "auto";
        Map<String, Object> config = request.getAdapterConfig();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModelKey() == null || request.getModelKey().isBlank()
                ? "MiniMaxAI/MiniMax-H3" : request.getModelKey());
        body.put("prompt", prompt == null || prompt.isBlank() ? GenerateMode.DEFAULT_I2V_PROMPT : prompt);
        body.put("task", task);
        body.put("seconds", seconds);
        body.put("conditions", conditions);
        int edge = 768;
        if (!config.containsKey("shortEdge") && request.getResolution() != null && !request.getResolution().isBlank()) {
            String resolution = request.getResolution().toLowerCase(Locale.ROOT).replace("p", "");
            try { edge = Integer.parseInt(resolution); } catch (NumberFormatException e) { throw bad("SGLang 分辨率请使用 768P 等短边像素值"); }
        }
        if (edge <= 0) throw bad("分辨率必须大于零");
        if (config.containsKey("shortEdge")) {
            try { edge = Integer.parseInt(String.valueOf(config.get("shortEdge"))); }
            catch (NumberFormatException e) { throw bad("shortEdge 必须为正整数"); }
            if (edge <= 0) throw bad("shortEdge 必须为正整数");
        }
        body.put("target", Map.of("short_edge", edge, "aspect_ratio", ratio, "duration_seconds", (double) seconds));
        // The content endpoint returns one MP4 per job.
        body.put("num_outputs_per_prompt", 1);
        body.put("num_inference_steps", config.getOrDefault("numInferenceSteps", 50));
        body.put("flow_shift", config.getOrDefault("flowShift", 12.0));
        body.put("audio_flow_shift", config.getOrDefault("audioFlowShift", 3.0));
        if (config.containsKey("seed")) body.put("seed", config.get("seed"));
        return body;
    }

    private void addFrame(List<Map<String, Object>> conditions, String key, int index) {
        if (key != null && !key.isBlank()) conditions.add(Map.of("type", "image", "uri", mediaUri(key),
                "role", "keyframe", "frame_index", index));
    }

    private String mediaUri(String key) {
        if (key.startsWith("http://") || key.startsWith("https://")) return key;
        // SGLang must be able to reach the configured object storage download address.
        return storage.presignGet(key, Duration.ofHours(24));
    }

    @Override public PollResult poll(String id) {
        Job job = jobs.get(id);
        if (job == null) return PollResult.failed(id, "SGLang 任务不存在");
        synchronized (job) {
            if (job.output != null) return PollResult.succeeded(id, job.output);
            if (System.currentTimeMillis() < job.nextPoll) return PollResult.running(id, job.progress);
            try {
                JsonNode response = json("GET", job.url(id), job.request.getApiKey(), null);
                String status = response.path("status").asText("");
                job.progress = Math.max(job.progress, Math.min(99, Math.max(0, response.path("progress").asInt(10))));
                if (Set.of("failed", "cancelled", "canceled").contains(status)) return PollResult.failed(id, error(response));
                if ("completed".equals(status)) {
                    byte[] bytes = http.request("GET", URI.create(job.url(id) + "/content"), job.request.getApiKey(), null);
                    if (bytes == null || bytes.length == 0) throw new BizException(ErrorCode.INTERNAL, "SGLang 成片为空");
                    String key = "users/" + job.request.getUserId() + "/outputs/" + job.request.getTaskId() + "/1.mp4";
                    storage.put(key, bytes, "video/mp4");
                    job.output = key;
                    return PollResult.succeeded(id, key);
                }
                job.nextPoll = System.currentTimeMillis() + 3000;
                return "queued".equals(status) ? PollResult.pending(id, job.progress) : PollResult.running(id, job.progress);
            } catch (BizException e) {
                String message = e.getMessage();
                if (message.contains("HTTP 429") || message.contains("HTTP 5") || message.contains("网络请求")) {
                    job.nextPoll = System.currentTimeMillis() + 5000;
                    return PollResult.running(id, job.progress);
                }
                return PollResult.failed(id, message);
            }
        }
    }

    @Override public void ping(GenerateRequest request) {
        json("GET", base(request.getEndpoint()) + "/models", request.getApiKey(), null);
    }

    /** Local cancellation only: SGLang's three-endpoint contract has no remote cancellation. */
    @Override public void cancel(String id) { jobs.remove(id); }

    private JsonNode json(String method, String url, String key, Map<String, Object> body) {
        try {
            JsonNode result = mapper.readTree(http.request(method, URI.create(url), key, body == null ? null : mapper.writeValueAsString(body)));
            if (result == null || !result.isObject()) throw new BizException(ErrorCode.INTERNAL, "SGLang 返回了空响应或非对象 JSON");
            return result;
        } catch (java.io.IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "SGLang JSON 请求或响应格式错误");
        }
    }

    private static String error(JsonNode response) {
        JsonNode error = response.path("error");
        return error.isTextual() ? error.asText() : error.path("message").asText("SGLang 生成失败");
    }
    private static BizException bad(String message) { return new BizException(ErrorCode.BAD_REQUEST, message); }
    private static final class Job {
        final GenerateRequest request;
        final String base;
        String output;
        long nextPoll;
        int progress;
        Job(GenerateRequest request, String base) { this.request = request; this.base = base; }
        String url(String id) { return base + "/videos/" + URLEncoder.encode(id, StandardCharsets.UTF_8); }
    }
}
