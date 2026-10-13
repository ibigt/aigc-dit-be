package cn.aigc.dit.generate;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "dit.generate")
public class GenerateProperties {
    private String defaultProvider = "mock";
    private Comfyui comfyui = new Comfyui();
    private Mock mock = new Mock();
    private Worker worker = new Worker();
    private Runninghub runninghub = new Runninghub();
    private Agnes agnes = new Agnes();
    private Ark ark = new Ark();
    private Minimax minimax = new Minimax();
    private Speech speech = new Speech();
    private OpenAiImage openAiImage = new OpenAiImage();
    private Ffmpeg ffmpeg = new Ffmpeg();
    private Vad vad = new Vad();
    private ForcedAlignment forcedAlignment = new ForcedAlignment();

    /**
     * ComfyUI Adapter 始终注册；实际地址以模型 {@code endpoint} 为准。
     * {@code enabled} 仅给 compose sidecar 使用，不影响管理端接入。
     */
    @Data
    public static class Comfyui {
        private boolean enabled = false;
        private String baseUrl = "http://127.0.0.1:8188";
        private int connectTimeoutSeconds = 15;
        private int requestTimeoutSeconds = 180;
        private int downloadTimeoutSeconds = 600;
    }

    @Data
    public static class Mock {
        private int delayMs = 2500;
    }

    @Data
    public static class Worker {
        private boolean enabled = true;
        private String stream = "dit:gen-tasks";
        private String group = "dit-workers";
        private int pollIntervalMs = 400;
        private int timeoutSeconds = 1800;
        /** 管理端健康条展示的并发上限，默认与短剧槽位一致。 */
        private int maxConcurrent = 8;
    }

    /**
     * RunningHub OpenAPI v2。文生图验证期强制 1k/low、copies=1。
     * {@code maxCalls <= 0} 表示不限制次数；需要护栏时再设正整数。
     * 默认走 economy 通道（约 $0.015/次）；官方 stable 把 {@code t2iPath}/{@code i2iPath} 改成
     * {@code rhart-image-g-2-official/text-to-image} 与
     * {@code rhart-image-g-2-official/image-to-image}。
     * 文生视频默认 seedance 2.0：{@code rhart-video/sparkvideo-2.0/text-to-video}。
     * 参考生视频：{@code rhart-video/sparkvideo-2.0/multimodal-video}。
     */
    @Data
    public static class Runninghub {
        private String baseUrl = "https://www.runninghub.ai";
        private String apiKey = "";
        private String t2iPath = "rhart-image-g-2/text-to-image";
        private String i2iPath = "rhart-image-g-2/image-to-image";
        private String t2vPath = "rhart-video/sparkvideo-2.0/text-to-video";
        private String r2vPath = "rhart-video/sparkvideo-2.0/multimodal-video";
        /** 成功提交次数上限；0 或负数表示不限制。 */
        private int maxCalls = 0;
        private String forceResolution = "1k";
        private String forceQuality = "low";
        private int connectTimeoutSeconds = 15;
        private int requestTimeoutSeconds = 120;
        /** 成片（尤其是视频）下载超时。 */
        private int downloadTimeoutSeconds = 600;
        private int downloadRetries = 3;
    }

    /**
     * Agnes 官方 REST：图片 {@code POST /v1/images/generations}，视频 {@code POST /v1/videos}。
     * 默认地址 {@code https://api.agnes-ai.cn/v1}；模型记录上的 API Key 优先，其次本配置。
     */
    @Data
    public static class Agnes {
        private String baseUrl = "https://api.agnes-ai.cn/v1";
        private String apiKey = "";
        private int connectTimeoutSeconds = 15;
        /** 文生图为同步接口，可能需要数分钟。 */
        private int requestTimeoutSeconds = 180;
        private int pollTimeoutSeconds = 30;
        /** 视频状态查询最小间隔；官方建议 1–2 秒，过密会 429。 */
        private int pollIntervalSeconds = 5;
        /** 命中查询限流后的起始退避秒数，随后指数增加，上限 60 秒。 */
        private int rateLimitBackoffSeconds = 8;
        private int downloadTimeoutSeconds = 600;
        private int downloadRetries = 3;
    }

    /**
     * 火山方舟官方 Seedance：{@code POST /contents/generations/tasks}，再 {@code GET} 轮询。
     * 默认地址 {@code https://ark.cn-beijing.volces.com/api/v3}；模型记录上的 API Key 优先。
     * 素材库是另一套管控面，用 AK/SK 调用 OpenAPI，不用这条 API Key。
     */
    @Data
    public static class Ark {
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";
        private String apiKey = "";
        private int connectTimeoutSeconds = 15;
        private int requestTimeoutSeconds = 120;
        private int pollTimeoutSeconds = 30;
        private int pollIntervalSeconds = 5;
        private int rateLimitBackoffSeconds = 8;
        private int downloadTimeoutSeconds = 600;
        private int downloadRetries = 3;
        /** 素材库 OpenAPI。与视频任务的 API Key 不是同一套凭证。 */
        private String accessKey = "";
        private String secretKey = "";
        private String projectName = "default";
        /** 留空时，第一次角色审核创建 AIGC 组并写回这里和对象存储。 */
        private String assetGroupId = "";
        private String assetEndpoint = "https://ark.cn-beijing.volcengineapi.com";
        private String assetRegion = "cn-beijing";
        private String assetService = "ark";
        private String assetApiVersion = "2024-01-01";
    }

    /**
     * MiniMax 官方视频 V2：{@code POST /v2/video_generation}，再 {@code GET /v2/query/video_generation/{id}}。
     * 默认地址 {@code https://api.minimax.cn}；模型记录上的按量 API Key 优先。不要用 Token Plan 订阅 Key。
     */
    @Data
    public static class Minimax {
        private String baseUrl = "https://api.minimax.cn";
        private String apiKey = "";
        private int connectTimeoutSeconds = 15;
        private int requestTimeoutSeconds = 120;
        private int pollTimeoutSeconds = 30;
        /** 官方建议约 10 秒轮询一次。 */
        private int pollIntervalSeconds = 10;
        private int rateLimitBackoffSeconds = 8;
        private int downloadTimeoutSeconds = 600;
        private int downloadRetries = 3;
    }

    /** OpenAI {@code POST /v1/audio/speech} 兼容语音合成接口。 */
    @Data
    public static class Speech {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private int connectTimeoutSeconds = 15;
        private int requestTimeoutSeconds = 180;
    }

    /**
     * OpenAI 官方 Images：{@code POST /v1/images/generations}、{@code POST /v1/images/edits}。
     * 适配协议 {@code gpt-image-2.0}/{@code gpt-image-2.5} 共用本运输层，仅模型标识不同。
     */
    @Data
    public static class OpenAiImage {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private int connectTimeoutSeconds = 15;
        /** 文生图/改图为同步接口，可能需要数分钟。 */
        private int requestTimeoutSeconds = 300;
    }

    /**
     * 成片拼接超时。stream copy 通常几十秒；编码不一致回退重编码时需更长。
     * 分镜出片烧字幕：有对白才烧；缺中文字体或 ffmpeg 失败时保留原片。
     */
    @Data
    public static class Ffmpeg {
        private int concatCopyTimeoutSeconds = 180;
        private int concatEncodeTimeoutSeconds = 900;
        private boolean captionBurnEnabled = false;
        /** 可选。不配则按 Linux Noto / Windows 微软雅黑等常见路径探测。 */
        private String captionFont = "";
        private int captionTimeoutSeconds = 180;
    }

    @Data
    public static class Vad {
        private boolean enabled = false;
        /** Silero VAD ONNX model path. The model is intentionally supplied by deployment. */
        private String modelPath = "";
        private float threshold = 0.5F;
        private int sampleRate = 16000;
        private int frameSamples = 512;
        private int minSpeechMs = 180;
        private int mergeGapMs = 240;
        private int paddingMs = 80;
    }

    /** In-process Chinese Charsiu forced-alignment settings. */
    @Data
    public static class ForcedAlignment {
        private boolean enabled = false;
        private String modelPath = "";
        /** Directory containing vocab_zh.json and the g2pm assets. */
        private String assetsPath = "";
        private int sampleRate = 16000;
        private int frameMs = 10;
        private int silenceThresholdFrames = 3;
        private double minConfidence = 0.0D;
        private double minCoverage = 0.90D;
        private String processorVersion = "forced-alignment-1";
    }
}
