package cn.aigc.dit.server.drama.agent;

/**
 * 剧本生成 Agent 统一契约。
 *
 * 4 个分析阶段（skeleton / script / assets / shots）都实现此接口，
 * 由 DramaAnalysisOrchestrator 统一编排。
 *
 * @param <I> 输入类型（每个 Agent 自定义）
 * @param <O> 输出类型（每个 Agent 自定义）
 */
public interface ScriptAgent<I, O> {

    /** 供默认实现打日志用。 */
    org.slf4j.Logger AGENT_LOG = org.slf4j.LoggerFactory.getLogger(ScriptAgent.class);

    /** Agent 类型，用于可观测归因（例：skeleton / script / assets / shots） */
    String agentType();

    /**
     * 主执行：调 LLM 生成结果（含内部重试、缩片、容错等）。
     * 实现需自行处理：
     * - prompt 拼装（从 ScriptPromptResolver 拉）
     * - LLM 调用（从 TextChatGateway）
     * - JSON 解析（Jackson + 重试 + 缩片）
     * - 异常处理
     */
    AgentResult<O> execute(AgentContext ctx, I input);

    /**
     * 默认实现：execute → supervisor → optional repair。
     *
     * 流程：
     * 1. 执行 Agent
     * 2. 若成功且 supervisor 非空，跑 supervisor 校验
     * 3. supervisor 不通过 → 把 issues 注入 prompt 重跑 1 次（限 1 次）
     *
     * 子类可覆写以定制修复反馈注入方式。
     */
    default AgentResult<O> executeWithSupervision(AgentContext ctx, I input, SupervisorAgent<O> supervisor) {
        AgentResult<O> result = execute(ctx, input);
        if (!result.success() || supervisor == null) {
            return result;
        }

        SupervisorVerdict verdict = supervisor.validate(ctx, result.output(), input);
        if (verdict.passed()) {
            AGENT_LOG.info("[{}-supervisor] 校验通过 agentType={} episodeId={}",
                    agentType(), agentType(), ctx.episodeId());
            return result;
        }

        // 只有 WARNING：不重跑。
        // 重跑是把反馈塞回输入让模型整篇重写 —— 剧本步尤其致命，第二遍的场次结构可能整个变样，
        // 而 WARNING 本来就是「提醒一下」，不是「不合格」。留档就够了。
        if (!verdict.hasErrors()) {
            AGENT_LOG.warn("[{}-supervisor] 校验有提醒（不阻塞，不重跑）agentType={} episodeId={} 问题数={}",
                    agentType(), agentType(), ctx.episodeId(), verdict.issues().size());
            logIssues(agentType(), ctx.episodeId(), verdict.issues());
            return result.withSupervisorIssues(verdict.issues());
        }

        // 不通过：尝试 1 次修复重试
        AGENT_LOG.warn("[{}-supervisor] 校验不通过 agentType={} episodeId={} 问题数={}，准备注入反馈修复重跑一次",
                agentType(), agentType(), ctx.episodeId(), verdict.issues().size());
        logIssues(agentType(), ctx.episodeId(), verdict.issues());

        I repairedInput = withRepairFeedback(input, verdict.issues());
        if (repairedInput == null) {
            // 子类未实现 feedback 注入，跳过 repair
            AGENT_LOG.warn("[{}-supervisor] 未实现反馈注入，跳过修复 agentType={} episodeId={}",
                    agentType(), agentType(), ctx.episodeId());
            return result;
        }
        AgentResult<O> repaired = execute(ctx, repairedInput);
        AGENT_LOG.info("[{}-supervisor] 修复重跑完成 agentType={} episodeId={} 修复后通过={}",
                agentType(), agentType(), ctx.episodeId(), repaired.success());
        return repaired.withSupervisorRepair(verdict.issues());
    }

    /** 打印前几条校验问题；完整内容见 agent_run.supervisor_issues 与管理后台。 */
    static void logIssues(String agentType, long episodeId, java.util.List<SupervisorIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }
        int limit = Math.min(issues.size(), 5);
        for (int i = 0; i < limit; i++) {
            SupervisorIssue issue = issues.get(i);
            AGENT_LOG.warn("[{}-supervisor]   校验问题 {}/{}: {} —— {}",
                    agentType, i + 1, issues.size(), issue.field(), issue.message());
        }
        if (issues.size() > limit) {
            AGENT_LOG.warn("[{}-supervisor]   还有 {} 条校验问题未展开（共 {} 条）",
                    agentType, issues.size() - limit, issues.size());
        }
    }

    /**
     * 把 supervisor issues 注入到输入中（用于重试）。
     * 子类必须覆写，否则 supervisor repair 不生效。
     *
     * @param input  原始输入
     * @param issues supervisor 给出的问题列表
     * @return 注入反馈后的新输入（返回 null 表示跳过 repair）
     */
    default I withRepairFeedback(I input, java.util.List<SupervisorIssue> issues) {
        return null;
    }
}
