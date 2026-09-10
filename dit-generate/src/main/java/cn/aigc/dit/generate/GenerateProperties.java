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

    /**
     * ComfyUI Adapter 始终注册；实际地址以模型 {@code endpoint} 为准。
     * {@code enabled} 仅给 compose sidecar 使用，不影响管理端接入。
     */
    @Data
    public static class Comfyui {
        private boolean enabled = false;
        private String baseUrl = "http://127.0.0.1:8188";
        private int connectTimeoutSeconds = 15;
        private int requestTimeoutSeconds = 60;
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
    }

    /**
     * RunningHub OpenAPI v2。文生图验证期强制 1k/low、copies=1。
     * {@code maxCalls <= 0} 表示不限制次数；需要护栏时再设正整数。
     * 默认走 economy 通道（约 $0.015/次）；官方 stable 把 {@code t2iPath} 改成
     * {@code rhart-image-g-2-official/text-to-image}。
     * 文生视频默认 seedance 2.0：{@code rhart-video/sparkvideo-2.0/text-to-video}。
     * 参考生视频：{@code rhart-video/sparkvideo-2.0/multimodal-video}。
     */
    @Data
    public static class Runninghub {
        private String baseUrl = "https://www.runninghub.ai";
        private String apiKey = "";
        private String t2iPath = "rhart-image-g-2/text-to-image";
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
}
