package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.ScriptDoc;

import java.util.List;

/**
 * AssetExtractAgent 输入：上游 ScriptDoc + 项目记忆。
 *
 * @param seriesId    所属剧集系列 ID
 * @param episodeId   所属剧集 ID（用于落库定位）
 * @param script      上游 ScriptParseAgent 生成的 ScriptDoc
 * @param propsSummary 所有 scene.props[] 汇总（自动从 script 计算，调用方也可覆盖）
 * @param memoryBlock 已渲染好的项目级记忆
 * @param feedback    supervisor 反馈（可空）。与道具汇总分开存：拆片抽取时每一片都要带上它，
 *                    混进道具汇总里会被模型当成道具名，抽出一堆不存在的资产
 */
public record AssetExtractReq(
        long seriesId,
        long episodeId,
        ScriptDoc script,
        List<String> propsSummary,
        String memoryBlock,
        String feedback
) {
    public AssetExtractReq(long seriesId, long episodeId, ScriptDoc script,
                           List<String> propsSummary, String memoryBlock) {
        this(seriesId, episodeId, script, propsSummary, memoryBlock, null);
    }

    public AssetExtractReq {
        if (seriesId <= 0) throw new IllegalArgumentException("seriesId must be positive");
        if (episodeId <= 0) throw new IllegalArgumentException("episodeId must be positive");
        if (script == null) throw new IllegalArgumentException("script must not be null");
    }
}
