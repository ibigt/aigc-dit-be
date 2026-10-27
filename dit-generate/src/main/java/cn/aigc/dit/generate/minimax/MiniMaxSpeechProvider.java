package cn.aigc.dit.generate.minimax;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProvider;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PingSupport;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** MiniMax 官方 {@code POST /v1/t2a_v2} 文本生成语音。 */
@Component
public class MiniMaxSpeechProvider implements GenerateProvider {

    public static final String KEY = "minimax-speech";
    static final String DEFAULT_MODEL = "speech-2.8-hd";
    static final String DEFAULT_VOICE = "male-qn-qingse";
    static final String DEFAULT_FORMAT = "mp3";
    private static final Set<String> FORMATS = Set.of("mp3", "wav", "flac", "pcm");

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Minimax properties;
    private final MiniMaxHttp http;
    private final ObjectMapper objectMapper;
    private final Map<String, java.util.List<String>> completed = new ConcurrentHashMap<>();

    @Autowired
    public MiniMaxSpeechProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkMiniMaxHttp(properties.getMinimax()), new ObjectMapper());
    }

    MiniMaxSpeechProvider(ObjectStorage objectStorage, GenerateProperties properties,
                          MiniMaxHttp http, ObjectMapper objectMapper) {
        this.objectStorage = objectStorage;
        this.properties = properties.getMinimax();
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
        String format = formatOf(request);
        java.util.List<String> keys = outputKeys(request, format);
        for (String key : keys) {
            String response = http.postJson(endpoint(request.getEndpoint(), properties.getBaseUrl()),
                    firstNonBlank(request.getApiKey(), properties.getApiKey()), toJson(request, format));
            JsonNode root = readJson(response);
            assertSuccess(root);
            byte[] audio = audioBytes(root);
            if (audio.length == 0) {
                throw new BizException(ErrorCode.INTERNAL, "MiniMax 语音接口未返回音频内容");
            }
            objectStorage.put(key, audio, contentType(format));
        }
        String jobId = "minimax-speech-" + UUID.randomUUID();
        completed.put(jobId, keys);
        return SubmitResult.accepted(jobId);
    }

    @Override
    public PollResult poll(String jobId) {
        java.util.List<String> keys = completed.get(jobId);
        return keys == null ? PollResult.failed(jobId, "MiniMax 语音任务不存在") : PollResult.succeeded(jobId, keys);
    }

    @Override
    public void cancel(String jobId) {
        completed.remove(jobId);
    }

    public void remember(String jobId, GenerateRequest request) {
        if (StringUtils.hasText(jobId) && request != null) {
            completed.putIfAbsent(jobId, outputKeys(request, formatOf(request)));
        }
    }

    /**
     * 用最小请求打 {@code t2a_v2}：空文本由服务端在参数校验阶段拒绝（业务错误，鉴权已通过），
     * 不会进入真实合成也不消耗字符额度；无效 Key 会在鉴权阶段返回 401/403。
     */
    @Override
    public void ping(GenerateRequest request) {
        String apiKey = firstNonBlank(request.getApiKey(), properties.getApiKey());
        if (apiKey == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MiniMax 语音测试连接需要 API Key");
        }
        URI uri = endpoint(request.getEndpoint(), properties.getBaseUrl());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", firstNonBlank(request.getModelKey(), DEFAULT_MODEL));
        body.put("text", "");
        body.put("stream", false);
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("voice_id", firstNonBlank(request.getVoiceId(),
                configString(request, "defaultVoice"), DEFAULT_VOICE));
        body.put("voice_setting", voice);
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("sample_rate", 32000);
        audio.put("bitrate", 128000);
        audio.put("format", firstNonBlank(request.getAudioFormat(),
                configString(request, "defaultFormat"), DEFAULT_FORMAT));
        audio.put("channel", 1);
        body.put("audio_setting", audio);
        body.put("language_boost", "auto");
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 MiniMax 语音测试连接请求失败");
        }
        try {
            String raw = http.postJson(uri, apiKey, json);
            JsonNode root = readJson(raw);
            int code = root.path("base_resp").path("status_code").asInt(0);
            if (code != 0 && PingSupport.looksLikeAuthFailure(root.path("base_resp").path("status_msg").asText(""))) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "MiniMax 语音鉴权失败：" + root.path("base_resp").path("status_msg").asText(""));
            }
        } catch (BizException e) {
            Integer status = PingSupport.httpStatusOf(e);
            if (status == null || PingSupport.isUnauthorized(status)) {
                throw e;
            }
        }
    }

    private void assertSupported(GenerateRequest request) {
        if ("music".equals(request.getAudioMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MiniMax 语音协议不支持 AI 音乐模式");
        }
        if (!ModelCategory.AUDIO.equalsIgnoreCase(request.getCategory())
                || !GenerateMode.T2A.equalsIgnoreCase(GenerateMode.canonical(request.getMode()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MiniMax 语音协议仅支持文本生成语音（t2a）");
        }
        if (!StringUtils.hasText(request.getPrompt())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "待朗读文本不能为空");
        }
    }

    private String toJson(GenerateRequest request, String format) {
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("voice_id", firstNonBlank(request.getVoiceId(), configString(request, "defaultVoice"), DEFAULT_VOICE));
        voice.put("speed", request.getSpeed() == null ? 1D : request.getSpeed());
        if (StringUtils.hasText(request.getEmotion())) voice.put("emotion", request.getEmotion());
        voice.put("vol", configDouble(request, "volume", 1D));
        voice.put("pitch", configInt(request, "pitch", 0));

        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("sample_rate", configInt(request, "sampleRate", 32000));
        audio.put("bitrate", configInt(request, "bitrate", 128000));
        audio.put("format", format);
        audio.put("channel", configInt(request, "channel", 1));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", firstNonBlank(request.getModelKey(), DEFAULT_MODEL));
        body.put("text", request.getPrompt().trim());
        body.put("stream", false);
        body.put("voice_setting", voice);
        body.put("audio_setting", audio);
        body.put("language_boost", firstNonBlank(configString(request, "languageBoost"), "auto"));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装 MiniMax 语音请求失败");
        }
    }

    static URI endpoint(String configured, String fallback) {
        String value = firstNonBlank(configured, fallback, AdapterCatalog.MINIMAX_DEFAULT_ENDPOINT)
                .replaceAll("/+$", "");
        if (value.endsWith("/v1/t2a_v2")) return URI.create(value);
        if (value.endsWith("/v1")) return URI.create(value + "/t2a_v2");
        return URI.create(value + "/v1/t2a_v2");
    }

    private JsonNode readJson(String response) {
        try {
            return objectMapper.readTree(response == null ? "" : response);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "MiniMax 语音响应不是合法 JSON");
        }
    }

    static void assertSuccess(JsonNode root) {
        int statusCode = root.path("base_resp").path("status_code").asInt(0);
        if (statusCode != 0) {
            String message = root.path("base_resp").path("status_msg").asText("MiniMax 语音生成失败");
            throw new BizException(ErrorCode.BAD_REQUEST, "MiniMax 语音生成失败（" + statusCode + "）：" + message);
        }
    }

    byte[] audioBytes(JsonNode root) {
        String value = root.path("data").path("audio").asText("").trim();
        if (!StringUtils.hasText(value)) return new byte[0];
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return http.getBytes(URI.create(value));
        }
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) value = value.substring(comma + 1);
        if (value.length() % 2 == 0 && value.matches("(?i)[0-9a-f]+")) {
            try {
                return HexFormat.of().parseHex(value);
            } catch (IllegalArgumentException ignored) {
                // fall through to base64
            }
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INTERNAL, "MiniMax 返回的音频编码无法识别");
        }
    }

    static String formatOf(GenerateRequest request) {
        String format = firstNonBlank(request.getAudioFormat(), configString(request, "defaultFormat"), DEFAULT_FORMAT)
                .toLowerCase(Locale.ROOT);
        return FORMATS.contains(format) ? format : DEFAULT_FORMAT;
    }

    static String outputKey(GenerateRequest request, String format) {
        return "users/" + (request.getUserId() == null ? 0 : request.getUserId()) + "/outputs/"
                + (request.getTaskId() == null ? 0 : request.getTaskId()) + "/1." + format;
    }

    private static java.util.List<String> outputKeys(GenerateRequest request, String format) {
        String first = outputKey(request, format);
        String prefix = first.substring(0, first.lastIndexOf('/') + 1);
        return java.util.stream.IntStream.rangeClosed(1, request.resolvedCopies()).mapToObj(index -> prefix + index + "." + format).toList();
    }

    static String contentType(String format) {
        return switch (format) {
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "pcm" -> "audio/L16";
            default -> "audio/mpeg";
        };
    }

    private static String configString(GenerateRequest request, String key) {
        Object value = request.getAdapterConfig() == null ? null : request.getAdapterConfig().get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static int configInt(GenerateRequest request, String key, int fallback) {
        Object value = request.getAdapterConfig() == null ? null : request.getAdapterConfig().get(key);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static double configDouble(GenerateRequest request, String key, double fallback) {
        Object value = request.getAdapterConfig() == null ? null : request.getAdapterConfig().get(key);
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }
}
