package cn.aigc.dit.server.drama.agent;

import java.util.List;

/**
 * Agent 执行结果：包含输出、supervisor 反馈、重试次数与 token 消耗。
 *
 * @param success               是否成功
 * @param output                业务输出（成功时非 null，失败时为 null）
 * @param error                 错误描述（成功时为 null）
 * @param supervisorRepaired    是否经过了 supervisor 反馈后的修复重试
 * @param supervisorIssues      supervisor 给出的具体问题列表
 * @param retryCount            内部重试次数（不含 supervisor repair）
 * @param supervisorRetryCount  supervisor 反馈后的修复重试次数（限 1）
 * @param promptTokens          累计 prompt token
 * @param completionTokens      累计 completion token
 */
public record AgentResult<T>(
        boolean success,
        T output,
        String error,
        boolean supervisorRepaired,
        List<SupervisorIssue> supervisorIssues,
        int retryCount,
        int supervisorRetryCount,
        long promptTokens,
        long completionTokens
) {
    public static <T> AgentResult<T> ok(T output) {
        return new AgentResult<>(true, output, null, false, List.of(), 0, 0, 0L, 0L);
    }

    public static <T> AgentResult<T> ok(T output, long promptTokens, long completionTokens) {
        return new AgentResult<>(true, output, null, false, List.of(), 0, 0, promptTokens, completionTokens);
    }

    public static <T> AgentResult<T> fail(String error) {
        return new AgentResult<>(false, null, error, false, List.of(), 0, 0, 0L, 0L);
    }

    public static <T> AgentResult<T> fail(String error, int retryCount) {
        return new AgentResult<>(false, null, error, false, List.of(), retryCount, 0, 0L, 0L);
    }

    public AgentResult<T> withSupervisorRepair(List<SupervisorIssue> issues) {
        return new AgentResult<>(success, output, error, true, issues, retryCount, supervisorRetryCount + 1,
                promptTokens, completionTokens);
    }

    /**
     * 记下 supervisor 的提醒，但不算「修复过」。
     *
     * <p>只有 WARNING 时用它：提醒要留档（agent_run.supervisor_issues），但不重跑模型。
     */
    public AgentResult<T> withSupervisorIssues(List<SupervisorIssue> issues) {
        return new AgentResult<>(success, output, error, supervisorRepaired, issues, retryCount, supervisorRetryCount,
                promptTokens, completionTokens);
    }

    public AgentResult<T> addRetry(long additionalPrompt, long additionalCompletion) {
        return new AgentResult<>(success, output, error, supervisorRepaired, supervisorIssues,
                retryCount + 1, supervisorRetryCount,
                promptTokens + additionalPrompt, completionTokens + additionalCompletion);
    }
}
