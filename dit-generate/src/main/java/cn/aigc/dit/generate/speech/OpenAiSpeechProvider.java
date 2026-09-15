package cn.aigc.dit.generate.speech;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProvider;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** OpenAI {@code /audio/speech} 兼容 TTS Provider。 */
@Component
public class OpenAiSpeechProvider implements GenerateProvider {

    public static final String KEY = "openai-speech";
    static final String DEFAULT_MODEL = "gpt-4o-mini-tts";
    static final String DEFAULT_VOICE = "alloy";
    static final String DEFAULT_FORMAT = "mp3";
    private static final Set<String> FORMATS = Set.of("mp3", "wav", "aac", "flac", "opus", "pcm");

    private final ObjectStorage objectStorage;
    private final GenerateProperties.Speech properties;
    private final SpeechHttp http;
    private final ObjectMapper objectMapper;
    private final Map<String, String> completed = new ConcurrentHashMap<>();

    @Autowired
    public OpenAiSpeechProvider(ObjectStorage objectStorage, GenerateProperties properties) {
        this(objectStorage, properties, new JdkSpeechHttp(properties.getSpeech()), new ObjectMapper());
    }

    OpenAiSpeechProvider(ObjectStorage objectStorage, GenerateProperties properties,
                         SpeechHttp http, ObjectMapper objectMapper) {
        this.objectStorage = objectStorage;
        this.properties = properties.getSpeech();
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
        byte[] audio = http.post(speechUri(request.getEndpoint(), properties.getBaseUrl()),
                firstNonBlank(request.getApiKey(), properties.getApiKey()), toJson(request, format));
        if (audio == null || audio.length == 0) {
            throw new BizException(ErrorCode.INTERNAL, "语音模型未返回音频内容");
        }
        String key = outputKey(request, format);
        objectStorage.put(key, audio, contentType(format));
        String jobId = "speech-" + UUID.randomUUID();
        completed.put(jobId, key);
        return SubmitResult.accepted(jobId);
    }

    @Override
    public PollResult poll(String jobId) {
        String key = completed.get(jobId);
        return key == null
                ? PollResult.failed(jobId, "语音生成任务不存在")
                : PollResult.succeeded(jobId, key);
    }

    @Override
    public void cancel(String jobId) {
        completed.remove(jobId);
    }

    /** 服务重启后 Worker 可根据已持久化任务恢复内存态。 */
    public void remember(String jobId, GenerateRequest request) {
        if (StringUtils.hasText(jobId) && request != null) {
            completed.putIfAbsent(jobId, outputKey(request, formatOf(request)));
        }
    }

    private void assertSupported(GenerateRequest request) {
        if (!ModelCategory.AUDIO.equalsIgnoreCase(request.getCategory())
                || !GenerateMode.T2A.equalsIgnoreCase(GenerateMode.canonical(request.getMode()))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前语音协议仅支持文本生成语音（t2a）");
        }
        if (!StringUtils.hasText(request.getPrompt())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "待朗读文本不能为空");
        }
        if (request.getPrompt().length() > 4096) {
            throw new BizException(ErrorCode.BAD_REQUEST, "待朗读文本不能超过 4096 个字符");
        }
        double speed = speedOf(request);
        if (speed < 0.25 || speed > 4.0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语速必须在 0.25 到 4.0 之间");
        }
    }

    private String toJson(GenerateRequest request, String format) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", firstNonBlank(request.getModelKey(), DEFAULT_MODEL));
        body.put("input", request.getPrompt().trim());
        body.put("voice", firstNonBlank(request.getVoiceId(), configString(request, "defaultVoice"), DEFAULT_VOICE));
        body.put("response_format", format);
        body.put("speed", speedOf(request));
        String instructions = configString(request, "instructions");
        if (StringUtils.hasText(instructions)) {
            body.put("instructions", instructions);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装语音生成请求失败");
        }
    }

    static URI speechUri(String endpoint, String fallback) {
        String base = firstNonBlank(endpoint, fallback, "https://api.openai.com/v1");
        base = base.replaceAll("/+$", "");
        if (!base.endsWith("/audio/speech")) {
            base += "/audio/speech";
        }
        return URI.create(base);
    }

    static String formatOf(GenerateRequest request) {
        String value = firstNonBlank(request.getAudioFormat(), configString(request, "defaultFormat"), DEFAULT_FORMAT)
                .toLowerCase(Locale.ROOT);
        return FORMATS.contains(value) ? value : DEFAULT_FORMAT;
    }

    static double speedOf(GenerateRequest request) {
        return request.getSpeed() == null ? 1.0 : request.getSpeed();
    }

    static String outputKey(GenerateRequest request, String format) {
        long userId = request.getUserId() == null ? 0L : request.getUserId();
        long taskId = request.getTaskId() == null ? 0L : request.getTaskId();
        return "users/" + userId + "/outputs/" + taskId + "/1." + format;
    }

    static String contentType(String format) {
        return switch (format) {
            case "wav" -> "audio/wav";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "opus" -> "audio/opus";
            case "pcm" -> "audio/L16";
            default -> "audio/mpeg";
        };
    }

    private static String configString(GenerateRequest request, String key) {
        Object value = request.getAdapterConfig() == null ? null : request.getAdapterConfig().get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
