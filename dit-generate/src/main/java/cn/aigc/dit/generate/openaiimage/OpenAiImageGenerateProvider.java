package cn.aigc.dit.generate.openaiimage;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI 官方 Images 运输层。{@code gpt-image-2.0}/{@code gpt-image-2.5} 共用本通道。
 * 文生图走 {@code /images/generations}；参考生图走 {@code /images/edits}。均为同步返回。
 */
@Slf4j
@Component
public class OpenAiImageGenerateProvider implements GenerateProvider {

    public static final String KEY = AdapterCatalog.TRANSPORT_OPENAI_IMAGE;

    private final ObjectStorage objectStorage;
    private final GenerateProperties.OpenAiImage properties;
    private final OpenAiImageHttp http;
    private final ObjectMapper objectMapper;
    private final Map<String, String> completed = new ConcurrentHashMap<>();

    @Autowired
    public OpenAiImageGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkOpenAiImageHttp(properties.getOpenAiImage()), new ObjectMapper());
    }

    OpenAiImageGenerateProvider(ObjectStorage objectStorage, GenerateProperties properties,
                                OpenAiImageHttp http, ObjectMapper objectMapper) {
        this.objectStorage = objectStorage;
        this.properties = properties.getOpenAiImage();
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
        String v1Base = OpenAiImageUrls.v1Base(request.getEndpoint(), properties.getBaseUrl());
        String mode = GenerateMode.canonical(safe(request.getMode()));
        JsonNode response;
        if (GenerateMode.I2I.equalsIgnoreCase(mode)) {
            response = submitEdit(request, v1Base, apiKey);
        } else {
            response = submitGenerate(request, v1Base, apiKey);
        }
        byte[] image = decodeImage(response);
        String key = ingestBytes(request, image);
        String jobId = "oai-img-" + UUID.randomUUID();
        completed.put(jobId, key);
        log.info("OpenAI image submit jobId={}, taskId={}, model={}, mode={}",
                jobId, request.getTaskId(), OpenAiImageRequestMapper.modelName(request), mode);
        return SubmitResult.accepted(jobId);
    }

    @Override
    public PollResult poll(String jobId) {
        String key = completed.get(jobId);
        return key == null
                ? PollResult.failed(jobId, "OpenAI Images 任务不存在")
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

    private JsonNode submitGenerate(GenerateRequest request, String v1Base, String apiKey) {
        Map<String, Object> body = OpenAiImageRequestMapper.toGenerateBody(request);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 OpenAI 文生图请求失败");
        }
        String raw = http.postJson(OpenAiImageUrls.toUri(OpenAiImageUrls.imagesGenerations(v1Base)), apiKey, json);
        return readTree(raw);
    }

    private JsonNode submitEdit(GenerateRequest request, String v1Base, String apiKey) {
        List<ImagePart> images = loadReferenceImages(request);
        if (images.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "参考生图至少需要一张图片");
        }
        String boundary = "----aigcDit" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipart = buildMultipart(boundary, OpenAiImageRequestMapper.toEditFields(request), images);
        String raw = http.postMultipart(
                OpenAiImageUrls.toUri(OpenAiImageUrls.imagesEdits(v1Base)), apiKey, multipart, boundary);
        return readTree(raw);
    }

    private void assertSupported(GenerateRequest request) {
        if (!ModelCategory.IMAGE.equalsIgnoreCase(request.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "OpenAI Images 仅支持图像模型");
        }
        if (!StringUtils.hasText(request.getPrompt())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        String adapterKey = AdapterCatalog.resolveKey(
                request.getAdapterKey(), "官方API", request.getCategory(), request.getModelKey());
        if (adapterKey != null
                && !AdapterCatalog.GPT_IMAGE_2_0.equals(adapterKey)
                && !AdapterCatalog.GPT_IMAGE_2_5.equals(adapterKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前 OpenAI Images 通道不支持该适配协议");
        }
        if (adapterKey != null && !AdapterCatalog.supportsMode(adapterKey, request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "GPT Image 官方协议仅支持文生图（t2i）与参考生图（i2i）");
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
                if (images > OpenAiImageRequestMapper.MAX_I2I_IMAGES) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "参考生图最多 " + OpenAiImageRequestMapper.MAX_I2I_IMAGES + " 张");
                }
            }
            if (images == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考生图至少需要一张图片");
            }
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "OpenAI Images 不支持该生成模式");
    }

    private List<ImagePart> loadReferenceImages(GenerateRequest request) {
        List<ImagePart> parts = new ArrayList<>();
        if (request.getReferences() == null) {
            return parts;
        }
        for (ReferenceAsset ref : request.getReferences()) {
            if (ref == null || !StringUtils.hasText(ref.getStorageKey())) {
                continue;
            }
            byte[] bytes = objectStorage.getBytes(ref.getStorageKey());
            if (bytes == null || bytes.length == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "参考图读取失败");
            }
            String filename = filenameOf(ref.getStorageKey());
            parts.add(new ImagePart(filename, contentTypeOf(filename), bytes));
            if (parts.size() >= OpenAiImageRequestMapper.MAX_I2I_IMAGES) {
                break;
            }
        }
        return parts;
    }

    private byte[] decodeImage(JsonNode response) {
        JsonNode item = response.path("data").path(0);
        String b64 = item.path("b64_json").asText("");
        if (StringUtils.hasText(b64)) {
            return decodeBase64(b64);
        }
        String url = item.path("url").asText("");
        if (StringUtils.hasText(url)) {
            throw new BizException(ErrorCode.INTERNAL, "OpenAI 返回了 URL 但当前通道仅处理 b64_json，请重试");
        }
        throw new BizException(ErrorCode.INTERNAL, "OpenAI 成功但未返回成片");
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
        throw new BizException(ErrorCode.BAD_REQUEST, "请配置 OpenAI API Key");
    }

    private JsonNode readTree(String raw) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "解析 OpenAI Images 响应失败");
        }
    }

    private static byte[] decodeBase64(String b64) {
        try {
            return Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL, "OpenAI 返回的图片数据无法解码");
        }
    }

    private static String outputKey(GenerateRequest request) {
        long userId = request.getUserId() == null ? 0L : request.getUserId();
        long taskId = request.getTaskId() == null ? 0L : request.getTaskId();
        return "users/" + userId + "/outputs/" + taskId + "/1.png";
    }

    private static String filenameOf(String storageKey) {
        String key = storageKey == null ? "" : storageKey;
        int idx = key.lastIndexOf('/');
        String name = idx >= 0 ? key.substring(idx + 1) : key;
        return StringUtils.hasText(name) ? name : "reference.png";
    }

    private static String contentTypeOf(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static byte[] buildMultipart(String boundary, Map<String, String> fields, List<ImagePart> images) {
        String dash = "--" + boundary;
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            text.append(dash).append("\r\n");
            text.append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n");
            text.append(entry.getValue() == null ? "" : entry.getValue()).append("\r\n");
        }
        byte[] head = text.toString().getBytes(StandardCharsets.UTF_8);
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(head);
        int total = head.length;
        for (ImagePart image : images) {
            String fieldName = images.size() == 1 ? "image" : "image[]";
            String meta = dash + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + fieldName
                    + "\"; filename=\"" + image.filename + "\"\r\n"
                    + "Content-Type: " + image.contentType + "\r\n\r\n";
            byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);
            byte[] nl = "\r\n".getBytes(StandardCharsets.UTF_8);
            chunks.add(metaBytes);
            chunks.add(image.bytes);
            chunks.add(nl);
            total += metaBytes.length + image.bytes.length + nl.length;
        }
        byte[] end = (dash + "--\r\n").getBytes(StandardCharsets.UTF_8);
        chunks.add(end);
        total += end.length;
        byte[] body = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, body, offset, chunk.length);
            offset += chunk.length;
        }
        return body;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ImagePart(String filename, String contentType, byte[] bytes) {
    }
}
