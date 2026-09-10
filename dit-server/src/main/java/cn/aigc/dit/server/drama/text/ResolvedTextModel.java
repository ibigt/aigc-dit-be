package cn.aigc.dit.server.drama.text;

/**
 * 项目绑定的文本模型（不含密钥）。无绑定时空 Optional，不要回退到「最近一条已启用模型」。
 */
public record ResolvedTextModel(
        long modelId,
        String name,
        String adapterKey,
        String provider,
        String endpoint,
        String chatModel
) {
}
