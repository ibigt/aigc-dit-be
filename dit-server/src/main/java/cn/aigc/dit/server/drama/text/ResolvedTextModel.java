package cn.aigc.dit.server.drama.text;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目绑定的文本模型（不含密钥）。无绑定时空 Optional，不要回退到「最近一条已启用模型」。
 */
public record ResolvedTextModel(
        long modelId,
        String name,
        String adapterKey,
        String provider,
        String endpoint,
        String chatModel,
        Map<String, Object> adapterConfig
) {
    public ResolvedTextModel(long modelId, String name, String adapterKey, String provider,
                             String endpoint, String chatModel) {
        this(modelId, name, adapterKey, provider, endpoint, chatModel, Map.of());
    }

    public ResolvedTextModel {
        Map<String, Object> configured = new LinkedHashMap<>();
        if (adapterConfig != null) {
            adapterConfig.forEach((key, value) -> {
                if (key != null && value != null) {
                    configured.put(key, value);
                }
            });
        }
        configured.putIfAbsent("enableThinking", false);
        adapterConfig = Collections.unmodifiableMap(configured);
    }
}
