package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;

/**
 * ShotDraftAgent 输入。
 *
 * @param seriesId    所属剧集系列 ID
 * @param episodeId   所属剧集 ID
 * @param script      上游 ScriptParseAgent 生成的 ScriptDoc
 * @param assets      上游 AssetExtractAgent 生成的资产清单（用于角色/道具名引用）
 * @param memoryBlock 已渲染好的项目级记忆
 * @param feedback    supervisor 反馈（可空）。与项目记忆分开存：反馈是「上一次输出哪里不对」，
 *                    混进记忆里会被当成设定，拆片重跑时每一片都该带上它
 */
public record ShotDraftReq(
        long seriesId,
        long episodeId,
        ScriptDoc script,
        AssetDraftList assets,
        String memoryBlock,
        String feedback
) {
    public ShotDraftReq(long seriesId, long episodeId, ScriptDoc script,
                         AssetDraftList assets, String memoryBlock) {
        this(seriesId, episodeId, script, assets, memoryBlock, null);
    }

    public ShotDraftReq {
        if (seriesId <= 0) throw new IllegalArgumentException("seriesId must be positive");
        if (episodeId <= 0) throw new IllegalArgumentException("episodeId must be positive");
        if (script == null) throw new IllegalArgumentException("script must not be null");
    }
}
