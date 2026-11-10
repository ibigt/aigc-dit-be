package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.agent.DramaAnalysisResult;

import java.util.Locale;

/**
 * 给用户看的措辞。
 *
 * <p>报错里不该甩内部代号：SKELETON、ScriptDoc、schema 校验、JSON 合同 —— 用户既看不懂，
 * 也不知道看到之后该做什么。这里统一成「哪一步 + 出了什么事」的中文说法；
 * 日志与 agent_run 表里仍然保留内部代号，方便排查。
 */
public final class DramaErrorText {

    private DramaErrorText() {
    }

    /** 编排步骤（骨架 → 剧本 → 资产 → 分镜）的中文名。 */
    public static String step(DramaAnalysisResult.Step step) {
        if (step == null) {
            return "分析";
        }
        return switch (step) {
            case SKELETON -> "故事骨架";
            case SCRIPT -> "剧本解析";
            case ASSETS -> "资产抽取";
            case SHOTS -> "分镜草稿";
        };
    }

    /** Agent 标签（skeleton / script / semantic_normalization / assets / shots）的中文名。 */
    public static String agent(String agentTag) {
        if (agentTag == null || agentTag.isBlank()) {
            return "分析";
        }
        return switch (agentTag.trim().toLowerCase(Locale.ROOT)) {
            case "skeleton" -> "故事骨架";
            case "script" -> "剧本解析";
            case "semantic_normalization" -> "语义归一化";
            case "assets" -> "资产抽取";
            case "shots" -> "分镜草稿";
            default -> "分析";
        };
    }

    /** v1 输出合同（ScriptDoc / AssetDraftList …）的中文名。 */
    public static String contract(String what) {
        if (what == null || what.isBlank()) {
            return "结果";
        }
        return switch (what) {
            case "ScriptDoc" -> "剧本";
            case "AssetDraftList" -> "资产清单";
            case "ShotDraftList" -> "分镜草稿";
            case "ShotEnrichList" -> "分镜补充信息";
            default -> "结果";
        };
    }

    /**
     * 模型返回的内容解析不出来时的说法。
     *
     * @param attempts 试了几次
     * @param reason   具体原因（人话）
     */
    public static String parseFailed(int attempts, String reason) {
        String head = "试了 " + attempts + " 次都没能解析出结果";
        return reason == null || reason.isBlank() ? head : head + "：" + reason;
    }

    /** 调用模型失败的说法。 */
    public static String modelCallFailed(String detail) {
        return detail == null || detail.isBlank()
                ? "调用文本模型失败"
                : "调用文本模型失败：" + detail;
    }
}
