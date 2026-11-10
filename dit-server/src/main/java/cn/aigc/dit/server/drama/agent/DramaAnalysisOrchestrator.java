package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.DramaPlaceNames;
import cn.aigc.dit.server.drama.ProjectMemoryService;
import cn.aigc.dit.server.drama.agent.impl.AssetExtractAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.AssetSupervisorImpl;
import cn.aigc.dit.server.drama.agent.impl.OutlineSkeletonAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.ScriptParseAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.ScriptSupervisorImpl;
import cn.aigc.dit.server.drama.agent.impl.SemanticNormalizationAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.SemanticNormalizationSupervisorImpl;
import cn.aigc.dit.server.drama.agent.impl.ShotDraftAgentImpl;
import cn.aigc.dit.server.drama.agent.impl.ShotSupervisorImpl;
import cn.aigc.dit.server.drama.agent.impl.SkeletonSupervisorImpl;
import cn.aigc.dit.server.drama.entity.DramaPlanData;
import cn.aigc.dit.server.drama.mapper.DramaPlanDataMapper;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptReferenceIndex;
import cn.aigc.dit.server.drama.text.SemanticNormalizationResult;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import cn.aigc.dit.server.drama.text.SkeletonDoc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧本分析编排器：把 4 个 Agent 串成一条流水线。
 *
 * 流水线：skeleton → script → assets → shots
 * 关键设计：
 * 1. 每一步独立失败可观测（不静默回退），用 DramaAnalysisResult.failedAt 标识断点
 * 2. 中间产物落库 drama_plan_data，key = skeleton / script_v2 / asset_draft / shot_draft
 * 3. 走完 script 步后增量更新 project memory
 * 4. 全量跑 + 单步重跑用同一个 entry point（runSkeleton/runScript/... 开关控制）
 * 5. 每次 Agent 调用都过 AgentRunLogService 落 agent_run 表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaAnalysisOrchestrator {

    /** drama_plan_data key 常量。 */
    public static final String KEY_SKELETON = "skeleton";
    public static final String KEY_SCRIPT = "script_v2";
    public static final String KEY_SCRIPT_RAW = "script_raw_agent";
    public static final String KEY_SEMANTIC_NORMALIZATION = "semantic_normalization";
    public static final String KEY_SCRIPT_NORMALIZED = "script_normalized";
    public static final String KEY_ASSETS = "asset_draft";
    public static final String KEY_SHOTS = "shot_draft";

    private final OutlineSkeletonAgentImpl skeletonAgent;
    private final SkeletonSupervisorImpl skeletonSupervisor;
    private final ScriptParseAgentImpl scriptAgent;
    private final ScriptSupervisorImpl scriptSupervisor;
    private final SemanticNormalizationAgentImpl semanticAgent;
    private final SemanticNormalizationSupervisorImpl semanticSupervisor;
    private final AssetExtractAgentImpl assetAgent;
    private final AssetSupervisorImpl assetSupervisor;
    private final ShotDraftAgentImpl shotAgent;
    private final ShotSupervisorImpl shotSupervisor;

    private final DramaPlanDataMapper planDataMapper;
    private final AgentRunLogService runLog;
    private final ProjectMemoryService memoryService;
    private final ObjectMapper objectMapper;

    /**
     * 主入口：跑指定步骤，返回累积结果。
     */
    public DramaAnalysisResult analyze(DramaAnalysisRequest req) {
        OffsetDateTime start = OffsetDateTime.now();
        AgentContext ctx = new AgentContext(
                req.seriesId(), req.episodeId(), req.sessionId(),
                req.promptVersion(), req.model() == null ? "" : req.model());
        DramaAnalysisResult.DramaAnalysisResultBuilder b = DramaAnalysisResult.builder();

        // 拉取记忆（一次性 4 步复用）
        String memoryBlock = memoryService.resolveMemoryBlock(req.seriesId());

        log.info("[orchestrator] ▶▶ 分析开始 seriesId={} episodeId={} sessionId={} model={} prompt版本={} "
                        + "剧本长度={}字 目标集数={} 步骤=[骨架={} 剧本={} 资产={} 分镜={}]",
                req.seriesId(), req.episodeId(), req.sessionId(), ctx.model(), req.promptVersion(),
                req.raw() == null ? 0 : req.raw().length(), req.targetEpisodes(),
                req.runSkeleton(), req.runScript(), req.runAssets(), req.runShots());

        // ─── 步骤 1：骨架 ───
        SkeletonDoc skeleton = req.existingSkeleton();
        if (req.runSkeleton()) {
            AgentResult<SkeletonDoc> r = runStep(ctx, DramaAnalysisResult.Step.SKELETON,
                    () -> skeletonAgent.executeWithSupervision(ctx,
                            new OutlineReq(req.seriesId(), req.raw(), req.targetEpisodes(), memoryBlock),
                            skeletonSupervisor));
            b.stepRetries(DramaAnalysisResult.Step.SKELETON, r.retryCount());
            b.stepSupervisorRepaired(DramaAnalysisResult.Step.SKELETON, r.supervisorRepaired());
            b.addTokens(r.promptTokens(), r.completionTokens());
            runLog.record("skeleton", ctx, r);
            if (!r.success()) {
                return finalize(b.failedAt(DramaAnalysisResult.Step.SKELETON)
                        .stepError(DramaAnalysisResult.Step.SKELETON, r.error())
                        .durationMs(AgentRunLogService.durationMs(start)).build());
            }
            skeleton = r.output();
            persist(ctx.episodeId(), KEY_SKELETON, skeleton, r.error(), "v2-agent");
        }
        b.skeleton(skeleton);

        // ─── 步骤 2：剧本解析 ───
        ScriptDoc script = req.existingScript();
        if (script != null) {
            // 重跑下游时用的是已存的剧本，里面可能留着「皇后的风仪宫，未央宫」这种并列地名，
            // 进流水线前先合并，别让下游再抽出一张讲同一个地方的场景图。
            script = DramaPlaceNames.mergeDuplicateLocations(script);
        }
        if (req.runScript()) {
            // 复制为 final 局部，避免 lambda 捕获被后续重新赋值的变量
            final SkeletonDoc skeletonForScript = skeleton;
            final String memoryForScript = memoryBlock;
            AgentResult<ScriptDoc> r = runStep(ctx, DramaAnalysisResult.Step.SCRIPT,
                    () -> scriptAgent.executeWithSupervision(ctx,
                            new ScriptParseReq(req.seriesId(), req.raw(), skeletonForScript, memoryForScript),
                            scriptSupervisor));
            b.stepRetries(DramaAnalysisResult.Step.SCRIPT, r.retryCount());
            b.stepSupervisorRepaired(DramaAnalysisResult.Step.SCRIPT, r.supervisorRepaired());
            b.addTokens(r.promptTokens(), r.completionTokens());
            runLog.record("script", ctx, r);
            if (!r.success()) {
                return finalize(b.failedAt(DramaAnalysisResult.Step.SCRIPT)
                        .stepError(DramaAnalysisResult.Step.SCRIPT, r.error())
                        .durationMs(AgentRunLogService.durationMs(start)).build());
            }
            script = r.output();
            persist(ctx.episodeId(), KEY_SCRIPT_RAW, script, r.error(), "v2-agent");

            final ScriptDoc rawScript = script;
            final SemanticNormalizationReq normalizationReq = new SemanticNormalizationReq(
                    req.seriesId(), req.episodeId(), rawScript,
                    ScriptReferenceIndex.from(rawScript), memoryBlock);
            AgentResult<SemanticNormalizationResult> normalized = runStep(
                    ctx, "SEMANTIC_NORMALIZATION",
                    () -> semanticAgent.executeWithSupervision(ctx, normalizationReq, semanticSupervisor));
            b.addTokens(normalized.promptTokens(), normalized.completionTokens());
            runLog.record("semantic_normalization", ctx, normalized);
            if (normalized.success()) {
                script = normalized.output().getNormalizedScript();
                persist(ctx.episodeId(), KEY_SEMANTIC_NORMALIZATION,
                        normalized.output(), null, "v2-agent");
            } else {
                // 归一化没过校验，但模型判出的别名分组通常还是对的：把它套回原始剧本
                // （原文是完好的），同一件东西就不会抽出两条资产。
                // 14 环境实测（MiniMax / episode 128）的重复资产就是这么来的：
                // 归一化被判死 → 直接回退原始剧本 → 别名写法原样进了资产清单。
                ScriptDoc salvaged = normalized.output() == null
                        ? null : normalized.output().getNormalizedScript();
                script = salvaged != null ? salvaged : rawScript;
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("success", false);
                fallback.put("fallback", true);
                fallback.put("salvaged_aliases", salvaged != null);
                fallback.put("error", normalized.error());
                fallback.put("supervisor_issues", normalized.supervisorIssues());
                persist(ctx.episodeId(), KEY_SEMANTIC_NORMALIZATION, fallback,
                        normalized.error(), "v2-agent-fallback");
                log.warn("[orchestrator] 语义归一化失败，{} episodeId={} reason={}",
                        salvaged != null ? "沿用模型判出的别名分组统一写法" : "回退原 ScriptDoc 并继续",
                        req.episodeId(), normalized.error());
            }

            // 地点去重：把「皇后的风仪宫，未央宫」这种并列了两处地方的地点名合并成一个。
            // 场景资产按地点抽取，同一处地方留两个名字，资产页就会出现两张讲同一个地方的场景图
            // （测试反馈的「资产里有两个场景」）。归一化那一步本该合并，这里再机械兜一次。
            script = DramaPlaceNames.mergeDuplicateLocations(script);
            persist(ctx.episodeId(), KEY_SCRIPT_NORMALIZED, script, null, "v2-agent");
            persist(ctx.episodeId(), KEY_SCRIPT, script, null, "v2-agent");

            // 用最终接受的剧本增量更新项目级记忆
            try {
                memoryService.recordAfterParse(req.seriesId(), req.episodeId(), script, skeleton);
            } catch (Exception e) {
                log.warn("[orchestrator] 更新项目记忆失败 seriesId={} episodeId={} msg={}",
                        req.seriesId(), req.episodeId(), e.getMessage());
            }
        }
        b.script(script);

        // ─── 步骤 3：资产抽取 ───
        if (req.runAssets()) {
            final ScriptDoc scriptForAssets = script;
            final List<String> propsSummary = AssetExtractAgentImpl.extractPropsSummary(script);
            final String memoryForAssets = memoryBlock;
            AgentResult<AssetDraftList> r = runStep(ctx, DramaAnalysisResult.Step.ASSETS,
                    () -> assetAgent.executeWithSupervision(ctx,
                            new AssetExtractReq(req.seriesId(), req.episodeId(), scriptForAssets, propsSummary, memoryForAssets),
                            assetSupervisor));
            b.stepRetries(DramaAnalysisResult.Step.ASSETS, r.retryCount());
            b.stepSupervisorRepaired(DramaAnalysisResult.Step.ASSETS, r.supervisorRepaired());
            b.addTokens(r.promptTokens(), r.completionTokens());
            runLog.record("assets", ctx, r);
            if (!r.success()) {
                return finalize(b.failedAt(DramaAnalysisResult.Step.ASSETS)
                        .stepError(DramaAnalysisResult.Step.ASSETS, r.error())
                        .durationMs(AgentRunLogService.durationMs(start)).build());
            }
            b.assets(r.output());
            persist(ctx.episodeId(), KEY_ASSETS, r.output(), r.error(), "v2-agent");
        }

        // ─── 步骤 4：分镜草稿 ───
        if (req.runShots()) {
            final ScriptDoc scriptForShots = script;
            final AssetDraftList assets = b.build().assets();
            final String memoryForShots = memoryBlock;
            AgentResult<ShotDraftList> r = runStep(ctx, DramaAnalysisResult.Step.SHOTS,
                    () -> shotAgent.executeWithSupervision(ctx,
                            new ShotDraftReq(req.seriesId(), req.episodeId(), scriptForShots, assets, memoryForShots),
                            shotSupervisor));
            b.stepRetries(DramaAnalysisResult.Step.SHOTS, r.retryCount());
            b.stepSupervisorRepaired(DramaAnalysisResult.Step.SHOTS, r.supervisorRepaired());
            b.addTokens(r.promptTokens(), r.completionTokens());
            runLog.record("shots", ctx, r);
            if (!r.success()) {
                return finalize(b.failedAt(DramaAnalysisResult.Step.SHOTS)
                        .stepError(DramaAnalysisResult.Step.SHOTS, r.error())
                        .durationMs(AgentRunLogService.durationMs(start)).build());
            }
            b.shots(r.output());
            persist(ctx.episodeId(), KEY_SHOTS, r.output(), r.error(), "v2-agent");
        }

        return finalize(b.durationMs(AgentRunLogService.durationMs(start)).build());
    }

    /**
     * 跑一步 Agent 并做防御性包裹。
     */
    private <O> AgentResult<O> runStep(AgentContext ctx, DramaAnalysisResult.Step step,
                                        java.util.function.Supplier<AgentResult<O>> runner) {
        return runStep(ctx, step.name(), runner);
    }

    /**
     * 带自定义标签的步骤执行。
     *
     * 语义归一化不是独立的 Step（它复用 SCRIPT 的失败归因），但日志标签必须区分开：
     * 否则日志里会出现两个「SCRIPT 步开始」，看的人以为 SCRIPT 跑了两遍。
     */
    private <O> AgentResult<O> runStep(AgentContext ctx, String label,
                                        java.util.function.Supplier<AgentResult<O>> runner) {
        long stepStart = System.currentTimeMillis();
        log.info("[orchestrator] ▶ {} 步开始 episodeId={} sessionId={} model={}",
                label, ctx.episodeId(), ctx.sessionId(), ctx.model());
        try {
            AgentResult<O> result = runner.get();
            long cost = System.currentTimeMillis() - stepStart;
            if (result != null && result.success()) {
                log.info("[orchestrator] ✔ {} 步完成 episodeId={} duration={}ms token=({}+{}) retry={} supervisor已修复={}",
                        label, ctx.episodeId(), cost, result.promptTokens(), result.completionTokens(),
                        result.retryCount(), result.supervisorRepaired());
            } else {
                log.warn("[orchestrator] ✘ {} 步失败 episodeId={} duration={}ms retry={} error={} 校验问题数={}",
                        label, ctx.episodeId(), cost,
                        result == null ? 0 : result.retryCount(),
                        result == null ? "步骤未返回结果" : result.error(),
                        result == null ? 0 : result.supervisorIssues().size());
                logIssues(label, ctx.episodeId(), result);
            }
            return result;
        } catch (Exception e) {
            log.error("[orchestrator] {} 步异常未捕获 episodeId={} class={} msg={}",
                    label, ctx.episodeId(), e.getClass().getSimpleName(), e.getMessage(), e);
            return AgentResult.fail("分析过程中出现异常：" + e.getMessage());
        }
    }

    /**
     * 打印 supervisor 的校验问题明细。整段可能很长，只打前几条 + 总数，
     * 完整内容在 agent_run.supervisor_issues 与管理后台可见。
     */
    private void logIssues(String label, long episodeId, AgentResult<?> result) {
        if (result == null || result.supervisorIssues() == null || result.supervisorIssues().isEmpty()) {
            return;
        }
        List<SupervisorIssue> issues = result.supervisorIssues();
        int limit = Math.min(issues.size(), 5);
        for (int i = 0; i < limit; i++) {
            SupervisorIssue issue = issues.get(i);
            log.warn("[orchestrator]   {} 校验问题 {}/{}: {} —— {}",
                    label, i + 1, issues.size(), issue.field(), issue.message());
        }
        if (issues.size() > limit) {
            log.warn("[orchestrator]   {} 还有 {} 条校验问题未展开（共 {} 条），完整内容见 agent_run.supervisor_issues",
                    label, issues.size() - limit, issues.size());
        }
    }

    /**
     * 落库中间态。
     */
    private void persist(long episodeId, String key, Object data, String error, String engine) {
        try {
            String json = objectMapper.writeValueAsString(data);
            planDataMapper.upsert(episodeId, key, json, engine, error);
        } catch (JsonProcessingException e) {
            log.warn("[orchestrator] 序列化失败 episodeId={} key={} msg={}", episodeId, key, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[orchestrator] 落库失败 episodeId={} key={} msg={}", episodeId, key, e.getMessage());
        }
    }

    /**
     * 收尾：统一打日志 + 返回。
     */
    private DramaAnalysisResult finalize(DramaAnalysisResult result) {
        if (result.success()) {
            log.info("[orchestrator] ■ 全流程成功 token=({}+{}) duration={}ms 各步重试={} 各步supervisor修复={}",
                    result.promptTokens(), result.completionTokens(), result.durationMs(),
                    result.stepRetries(), result.stepSupervisorRepaired());
        } else {
            log.warn("[orchestrator] ■ 流程在 {} 步终止 reason={} duration={}ms token=({}+{}) 各步重试={}",
                    result.failedAt(), result.stepErrors().get(result.failedAt()), result.durationMs(),
                    result.promptTokens(), result.completionTokens(), result.stepRetries());
        }
        return result;
    }

    /**
     * 只跑下游（assets + shots），剧本已存在时使用。
     */
    public DramaAnalysisResult regenerateDownstream(DramaAnalysisRequest req) {
        return analyze(req);
    }
}
