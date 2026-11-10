package cn.aigc.dit.server.drama.agent;

/**
 * OutlineSkeletonAgent 输入：原文 + 目标集数 + 项目记忆。
 *
 * @param seriesId       所属剧集系列 ID
 * @param raw            原始剧本/小说/创意文本
 * @param targetEpisodes 目标集数（null 时自动推断；显式传入会强制对齐）
 * @param memoryBlock    已渲染好的项目级记忆 Markdown（来自 ProjectMemoryService）
 */
public record OutlineReq(
        long seriesId,
        String raw,
        Integer targetEpisodes,
        String memoryBlock
) {
    public OutlineReq {
        if (seriesId <= 0) {
            throw new IllegalArgumentException("seriesId must be positive");
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("raw must not be blank");
        }
    }
}
