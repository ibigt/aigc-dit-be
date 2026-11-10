package cn.aigc.dit.server.drama.agent;

/**
 * Agent 执行上下文：跨调用传递身份与版本信息，用于可观测归因。
 *
 * @param seriesId    所属剧集系列 ID
 * @param episodeId   所属剧集 ID
 * @param sessionId   一次 analyzeScript 调用内的会话 ID（贯穿 4 个 Agent）
 * @param promptVersion  prompt 版本号（来自 script_prompt.version），用于 A/B 对比
 * @param model       实际调用的文本模型标识
 */
public record AgentContext(
        long seriesId,
        long episodeId,
        String sessionId,
        String promptVersion,
        String model
) {
    public AgentContext {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
}
