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

    @Data
    public static class Comfyui {
        private boolean enabled = false;
        private String baseUrl = "http://127.0.0.1:8188";
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
        private int timeoutSeconds = 120;
    }
}
