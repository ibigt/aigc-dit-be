package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.SkeletonDoc;

/**
 * ScriptParseAgent 输入：原文 + 骨架 + 项目记忆。
 *
 * @param seriesId    所属剧集系列 ID
 * @param raw         原始剧本/小说文本（原文本身，不含反馈）
 * @param skeleton    上游 OutlineSkeletonAgent 生成的骨架（可空，null 时走无骨架模式）
 * @param memoryBlock 已渲染好的项目级记忆 Markdown
 * @param feedback    supervisor 反馈（可空）。与原文分开存：原文要拿去切分片，
 *                    反馈得每一片都带上，混在原文里只会落在最后一片上
 */
public record ScriptParseReq(
        long seriesId,
        String raw,
        SkeletonDoc skeleton,
        String memoryBlock,
        String feedback
) {
    public ScriptParseReq(long seriesId, String raw, SkeletonDoc skeleton, String memoryBlock) {
        this(seriesId, raw, skeleton, memoryBlock, null);
    }

    public ScriptParseReq {
        if (seriesId <= 0) {
            throw new IllegalArgumentException("seriesId must be positive");
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("raw must not be blank");
        }
    }
}
