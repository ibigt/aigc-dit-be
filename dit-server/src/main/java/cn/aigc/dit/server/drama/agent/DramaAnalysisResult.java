package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import cn.aigc.dit.server.drama.text.SkeletonDoc;

import java.util.List;

/**
 * DramaAnalysisOrchestrator 一次性输出：4 个 Agent 各自的结果与可观测性。
 *
 * @param success         全流程是否走完（true = 4 步全部成功）
 * @param failedAt        失败发生在哪一步（null 表示全部成功）
 * @param skeleton        OutlineSkeletonAgent 输出
 * @param script          ScriptParseAgent 输出
 * @param assets          AssetExtractAgent 输出
 * @param shots           ShotDraftAgent 输出
 * @param stepErrors      每一步的错误信息（key = skeleton/script/assets/shots，value = error）
 * @param stepRetries     每一步的重试次数（仅内部重试，不含 supervisor repair）
 * @param stepSupervisorRepaired 每一步是否走过 supervisor 修复
 * @param promptTokens    4 步累计 prompt token
 * @param completionTokens 4 步累计 completion token
 * @param durationMs      全流程总耗时
 */
public record DramaAnalysisResult(
        boolean success,
        Step failedAt,
        SkeletonDoc skeleton,
        ScriptDoc script,
        AssetDraftList assets,
        ShotDraftList shots,
        java.util.Map<Step, String> stepErrors,
        java.util.Map<Step, Integer> stepRetries,
        java.util.Map<Step, Boolean> stepSupervisorRepaired,
        long promptTokens,
        long completionTokens,
        long durationMs
) {

    /** 编排阶段标识。 */
    public enum Step {
        SKELETON, SCRIPT, ASSETS, SHOTS
    }

    public static DramaAnalysisResultBuilder builder() {
        return new DramaAnalysisResultBuilder();
    }

    /**
     * Builder：按调用顺序累加 4 步的产物。
     */
    public static class DramaAnalysisResultBuilder {
        private SkeletonDoc skeleton;
        private ScriptDoc script;
        private AssetDraftList assets;
        private ShotDraftList shots;
        private Step failedAt;
        private final java.util.LinkedHashMap<Step, String> stepErrors = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<Step, Integer> stepRetries = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<Step, Boolean> stepSupervisorRepaired = new java.util.LinkedHashMap<>();
        private long promptTokens;
        private long completionTokens;
        private long durationMs;

        public DramaAnalysisResultBuilder skeleton(SkeletonDoc v) { this.skeleton = v; return this; }
        public DramaAnalysisResultBuilder script(ScriptDoc v) { this.script = v; return this; }
        public DramaAnalysisResultBuilder assets(AssetDraftList v) { this.assets = v; return this; }
        public DramaAnalysisResultBuilder shots(ShotDraftList v) { this.shots = v; return this; }
        public DramaAnalysisResultBuilder failedAt(Step s) { this.failedAt = s; return this; }
        public DramaAnalysisResultBuilder stepError(Step s, String err) { stepErrors.put(s, err); return this; }
        public DramaAnalysisResultBuilder stepRetries(Step s, int n) { stepRetries.put(s, n); return this; }
        public DramaAnalysisResultBuilder stepSupervisorRepaired(Step s, boolean b) {
            stepSupervisorRepaired.put(s, b);
            return this;
        }
        public DramaAnalysisResultBuilder addTokens(long p, long c) { this.promptTokens += p; this.completionTokens += c; return this; }
        public DramaAnalysisResultBuilder durationMs(long d) { this.durationMs = d; return this; }

        public DramaAnalysisResult build() {
            boolean ok = failedAt == null
                    && skeleton != null && script != null && assets != null && shots != null;
            return new DramaAnalysisResult(ok, failedAt, skeleton, script, assets, shots,
                    stepErrors, stepRetries, stepSupervisorRepaired,
                    promptTokens, completionTokens, durationMs);
        }
    }

    /** 全流程成功的判断（4 步都成功且失败步为 null）。 */
    public boolean allStepsOk() {
        return success;
    }

    /** 列出成功的步骤。 */
    public List<Step> successfulSteps() {
        java.util.List<Step> ok = new java.util.ArrayList<>();
        if (skeleton != null) ok.add(Step.SKELETON);
        if (script != null) ok.add(Step.SCRIPT);
        if (assets != null) ok.add(Step.ASSETS);
        if (shots != null) ok.add(Step.SHOTS);
        return ok;
    }
}
