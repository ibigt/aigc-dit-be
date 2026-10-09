package cn.aigc.dit.generate.seedream;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.constant.ModelCategory;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 火山方舟 Seedream 图像运输层。
 * {@code seedream-4.0}/{@code seedream-4.5} 走官方 {@code /api/v3}；
 * {@code seedream-5.0} 走 token plan {@code /api/plan/v3}。均为同步 {@code /images/generations}。
 */
@Slf4j
@Component
public class SeedreamGenerateProvider implements GenerateProvider {

    public static final String KEY = AdapterCatalog.TRANSPORT_SEEDREAM;

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Ark properties;
    private final SeedreamHttp http;
    private final ObjectMapper objectMapper;
    private final Map<String, String> completed = new ConcurrentHashMap<>();

    @Autowired
    public SeedreamGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkSeedreamHttp(properties.getArk()), new ObjectMapper());
    }

    SeedreamGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                             SeedreamHttp http, ObjectMapper objectMapper) {
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
        String v3Base = SeedreamUrls.v3Base(request.getEndpoint(), properties.getBaseUrl(), null);
        List<String> images = loadReferenceDataUris(request);
        Map<String, Object> body = SeedreamRequestMapper.toBody(request, images);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 Seedream 请求失败");
        }
        String raw = http.postJson(SeedreamUrls.toUri(SeedreamUrls.imagesGenerations(v3Base)), apiKey, json);
        JsonNode response = readTree(raw);
        String originUrl = response.path("data").path(0).path("url").asText("");
        byte[] image = decodeImage(response);
        String key = ingestBytes(request, image);
        if (SeedreamOriginUrls.qualifies(request)) {
            SeedreamOriginUrls.save(objectStorage, objectMapper, key, originUrl, Instant.now());
        }
        String jobId = "seedream-" + UUID.randomUUID();
        completed.put(jobId, key);
        log.info("Seedream submit jobId={}, taskId={}, model={}, mode={}, base={}",
                jobId, request.getTaskId(), SeedreamRequestMapper.modelName(request),
                GenerateMode.canonical(safe(request.getMode())), v3Base);
        return SubmitResult.accepted(jobId);
    }

    @Override
    public PollResult poll(String jobId) {
        String key = completed.get(jobId);
        return key == null
                ? PollResult.failed(jobId, "Seedream 任务不存在")
                : PollResult.succeeded(jobId, key);
    }

    @Override
    public void cancel(String jobId) {
        completed.remove(jobId);
    }

    public void remember(String jobId, GenerateRequest request) {
        if (StringUtils.hasText(jobId) && request != null) {
            completed.putIfAbsent(jobId, outputKey(request));
        }
    }

    private void assertSupported(GenerateRequest request) {
        if (!ModelCategory.IMAGE.equalsIgnoreCase(request.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Seedream 仅支持图像模型");
        }
        if (!StringUtils.hasText(request.getPrompt())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        String adapterKey = AdapterCatalog.resolveKey(
                request.getAdapterKey(), null, request.getCategory(), request.getModelKey());
        if (adapterKey != null
                && !AdapterCatalog.SEEDREAM_4_0.equals(adapterKey)
                && !AdapterCatalog.SEEDREAM_4_5.equals(adapterKey)
                && !AdapterCatalog.SEEDREAM_5_0.equals(adapterKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前 Seedream 通道不支持该适配协议");
        }
        if (adapterKey != null && !AdapterCatalog.supportsMode(adapterKey, request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Seedream 仅支持文生图（t2i）与参考生图（i2i）");
        }
        String mode = GenerateMode.canonical(safe(request.getMode()));
        if (GenerateMode.T2I.equalsIgnoreCase(mode)) {
            if (request.getReferences() != null && !request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文生图请移除参考图，或改用参考生图（i2i）");
            }
            return;
        }
        if (GenerateMode.I2I.equalsIgnoreCase(mode)) {
            if (request.getReferences() == null || request.getReferences().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生图至少需要一张图片");
            }
            int images = 0;
            for (ReferenceAsset ref : request.getReferences()) {
                if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                    continue;
                }
                if (AssetKind.VIDEO.equalsIgnoreCase(ref.getType())
                        || AssetKind.AUDIO.equalsIgnoreCase(ref.getType())) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "参考生图仅支持图片素材");
                }
                images++;
                if (images > SeedreamRequestMapper.MAX_I2I_IMAGES) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "参考生图最多 " + SeedreamRequestMapper.MAX_I2I_IMAGES + " 张");
                }
            }
            if (images == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生图至少需要一张图片");
            }
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "Seedream 不支持该生成模式");
    }

    private List<String> loadReferenceDataUris(GenerateRequest request) {
        List<String> images = new ArrayList<>();
        if (request.getReferences() == null) {
            return images;
        }
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            byte[] bytes = objectStorage.getBytes(ref.getStorageKey());
            if (bytes == null || bytes.length == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考图读取失败");
            }
            String mime = contentTypeOf(ref.getStorageKey());
            images.add("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
            if (images.size() >= SeedreamRequestMapper.MAX_I2I_IMAGES) {
                break;
            }
        }
        return images;
    }

    private byte[] decodeImage(JsonNode response) {
        JsonNode item = response.path("data").path(0);
        String b64 = item.path("b64_json").asText("");
        if (StringUtils.hasText(b64)) {
            return decodeBase64(b64);
        }
        String url = item.path("url").asText("");
        if (StringUtils.hasText(url)) {
            return http.getBytes(SeedreamUrls.toUri(url.trim()));
        }
        throw new BizException(ErrorCode.INTERNAL, "Seedream 成功但未返回成片");
    }

    private String ingestBytes(GenerateRequest request, byte[] bytes) {
        String key = outputKey(request);
        objectStorage.put(key, bytes, "image/png");
        return key;
    }

    private String resolveApiKey(GenerateRequest request) {
        if (StringUtils.hasText(request.getApiKey())) {
            return request.getApiKey().trim();
        }
        if (StringUtils.hasText(properties.getApiKey())) {
            return properties.getApiKey().trim();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "请配置火山方舟 API Key");
    }

    private JsonNode readTree(String raw) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "解析 Seedream 响应失败");
        }
    }

    private static byte[] decodeBase64(String b64) {
        try {
            return Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL, "Seedream 返回的图片数据无法解码");
        }
    }

    private static String outputKey(GenerateRequest request) {
        long userId = request.getUserId() == null ? 0L : request.getUserId();
        long taskId = request.getTaskId() == null ? 0L : request.getTaskId();
        return "users/" + userId + "/outputs/" + taskId + "/1.png";
    }

    private static String contentTypeOf(String storageKey) {
        String lower = storageKey == null ? "" : storageKey.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
