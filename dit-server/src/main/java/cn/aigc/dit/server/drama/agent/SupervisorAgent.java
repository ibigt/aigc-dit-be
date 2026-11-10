package cn.aigc.dit.server.drama.agent;

import java.util.List;

/**
 * Supervisor 校验器：对 Agent 输出做结构 + 语义校验。
 *
 * @param <O> 被校验的 Agent 输出类型
 */
public interface SupervisorAgent<O> {

    /** Supervisor 类型，用于可观测归因（例：script_sup / assets_sup） */
    String supervisorType();

    /**
     * 校验 Agent 输出。
     *
     * @param ctx           Agent 上下文
     * @param output        Agent 产出
     * @param originalInput Agent 接收的原始输入（用于跨阶段一致性校验）
     * @return 校验结果
     */
    SupervisorVerdict validate(AgentContext ctx, O output, Object originalInput);

    /**
     * 把 issues 渲染为修复反馈文本。子类可覆写以定制格式。
     */
    default String renderRepairFeedback(List<SupervisorIssue> issues) {
        StringBuilder sb = new StringBuilder();
        for (SupervisorIssue issue : issues) {
            sb.append("- [").append(issue.severity()).append("] ")
              .append(issue.field()).append(": ").append(issue.message()).append("\n");
        }
        return sb.toString();
    }
}
