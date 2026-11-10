package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.SkeletonDoc;

/**
 * DramaAnalysisOrchestrator 输入。
 *
 * @param seriesId       所属剧集系列 ID
 * @param episodeId      所属剧集 ID
 * @param sessionId      一次 analyzeScript 调用内的会话 ID（贯穿 4 个 Agent）
 * @param raw            原始剧本/小说文本
 * @param targetEpisodes 目标集数（null 时由 Agent 推断）
 * @param existingSkeleton 上游已生成的骨架（可空；为空时由 orchestrator 内 OutlineSkeletonAgent 跑一次）
 * @param existingScript   上游已生成的 ScriptDoc（可空；为空时由 orchestrator 内 ScriptParseAgent 跑一次）
 * @param promptVersion  prompt 版本号（用于 A/B 对比）
 * @param model          实际调用的模型标识
 * @param runSkeleton    是否跑 OutlineSkeletonAgent（false 时复用 existingSkeleton）
 * @param runScript      是否跑 ScriptParseAgent（false 时复用 existingScript）
 * @param runAssets      是否跑 AssetExtractAgent
 * @param runShots       是否跑 ShotDraftAgent
 */
public record DramaAnalysisRequest(
        long seriesId,
        long episodeId,
        String sessionId,
        String raw,
        Integer targetEpisodes,
        SkeletonDoc existingSkeleton,
        cn.aigc.dit.server.drama.text.ScriptDoc existingScript,
        String promptVersion,
        String model,
        boolean runSkeleton,
        boolean runScript,
        boolean runAssets,
        boolean runShots
) {
    public DramaAnalysisRequest {
        if (seriesId <= 0) throw new IllegalArgumentException("seriesId must be positive");
        if (episodeId <= 0) throw new IllegalArgumentException("episodeId must be positive");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("raw must not be blank");
        }
    }

    /**
     * 工厂：全量运行 4 步。
     */
    public static DramaAnalysisRequest fullRun(long seriesId, long episodeId, String sessionId,
                                                String raw, Integer targetEpisodes,
                                                String promptVersion, String model) {
        return new DramaAnalysisRequest(seriesId, episodeId, sessionId, raw, targetEpisodes,
                null, null, promptVersion, model, true, true, true, true);
    }

    /**
     * 工厂：仅跑 assets + shots（剧本已存在）。
     */
    public static DramaAnalysisRequest downstreamOnly(long seriesId, long episodeId, String sessionId,
                                                       String raw, SkeletonDoc skeleton,
                                                       cn.aigc.dit.server.drama.text.ScriptDoc script,
                                                       String promptVersion, String model) {
        return new DramaAnalysisRequest(seriesId, episodeId, sessionId, raw, null,
                skeleton, script, promptVersion, model, false, false, true, true);
    }
}
