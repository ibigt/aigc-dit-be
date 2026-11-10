package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.DramaErrorText;
import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AgentResult;
import cn.aigc.dit.server.drama.agent.OutlineReq;
import cn.aigc.dit.server.drama.agent.OutlineSkeletonAgent;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.text.ScriptPromptResolver;
import cn.aigc.dit.server.drama.text.SkeletonDoc;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.drama.text.TextChatRequest;
import cn.aigc.dit.server.drama.text.TextChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 故事骨架 Agent 实现。负责调用 LLM 生成 SkeletonDoc，含 2 次重试 + supervisor 反馈注入。
 *
 * <p>解析与预算处理跟 v2 其余四个 Agent（script / semantic_normalization / assets / shots）
 * 一样走 {@link AgentTruncationSupport}：
 * <ol>
 *   <li>容错解析：剥代码围栏、剥 JSON 前后的说明文字、容错语法（注释 / 尾逗号 / 单引号）；</li>
 *   <li>识别 {@code finish_reason=length} 的截断，把预算加倍到 {@code MAX_TOKENS_CEILING} 重放；</li>
 *   <li>失败时把真实原因写进错误信息，不再一律报「未通过 schema 校验」。</li>
 * </ol>
 *
 * <p>为什么必须是这一套：之前这里只做严格反序列化，而 deepseek 这类模型习惯在 JSON 前面
 * 写一段「好的，以下是分集骨架：」，严格反序列化直接判死；被 {@code max_tokens} 截断时
 * 又是把同一个请求原样重发两次（预算没变，必然再被截断一次）。于是 deepseek 下
 * SKELETON 步必挂，且失败原因被笼统的「重试 2 次均未通过 schema 校验」盖住，
 * 连是模型返回非法 JSON、被截断，还是网关直接报了 HTTP 400 都分不出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutlineSkeletonAgentImpl implements OutlineSkeletonAgent {

    /** 骨架生成单次请求最大 token（不被截断的上限；超出会触发截断策略）。 */
    private static final int MAX_TOKENS = 8000;

    /** 截断后允许加倍到的上限（与 v1 老链路、v2 其余 Agent 一致）。 */
    private static final int MAX_TOKENS_CEILING = AgentTruncationSupport.MAX_TOKENS_CEILING;

    /** 内部重试次数（不含 supervisor repair）。 */
    private static final int INTERNAL_RETRIES = 2;

    private final TextChatGateway gateway;
    private final ScriptPromptResolver promptResolver;
    private final ObjectMapper objectMapper;

    @Override
    public AgentResult<SkeletonDoc> execute(AgentContext ctx, OutlineReq input) {
        long promptTokens = 0L;
        long completionTokens = 0L;
        int tokens = MAX_TOKENS;
        String reason = "";

        for (int attempt = 1; attempt <= INTERNAL_RETRIES; attempt++) {
            Outcome outcome = requestOnce(ctx, input, tokens, attempt);
            promptTokens += outcome.promptTokens();
            completionTokens += outcome.completionTokens();
            if (outcome.doc() != null) {
                if (attempt > 1) {
                    log.info("[skeleton-agent] 第 {} 次输出符合 schema episodeId={}", attempt, ctx.episodeId());
                }
                return AgentResult.ok(outcome.doc(), promptTokens, completionTokens);
            }
            reason = outcome.reason();
            if (outcome.truncated() && tokens < MAX_TOKENS_CEILING) {
                tokens = MAX_TOKENS_CEILING;
                log.warn("[skeleton-agent] 输出被 max_tokens 截断，加倍预算重放 episodeId={} maxTokens={}",
                        ctx.episodeId(), tokens);
            }
        }
        // 重试耗尽：返回失败，并把最后一次的真实原因带出去（区分「模型没给合法 JSON」、
        // 「被 max_tokens 截断」、「网关直接报错」三种情况，别再一律叫 schema 校验没过）。
        return AgentResult.fail(DramaErrorText.parseFailed(INTERNAL_RETRIES, reason), INTERNAL_RETRIES);
    }

    @Override
    public OutlineReq withRepairFeedback(OutlineReq input, List<SupervisorIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return input;
        }
        StringBuilder feedback = new StringBuilder();
        feedback.append("\n\n【Supervisor 反馈，必须修复以下问题】\n");
        for (SupervisorIssue issue : issues) {
            feedback.append("- [").append(issue.severity()).append("] ")
                    .append(issue.field()).append(": ").append(issue.message());
            if (issue.suggestion() != null && !issue.suggestion().isBlank()) {
                feedback.append(" → 建议：").append(issue.suggestion());
            }
            feedback.append("\n");
        }
        return new OutlineReq(input.seriesId(), input.raw() + feedback, input.targetEpisodes(), input.memoryBlock());
    }

    /**
     * 发一次请求并解析。
     *
     * @param maxTokens 本次请求的输出预算（被截断时会加倍重放）
     * @param attempt   第几次尝试（日志用）
     */
    private Outcome requestOnce(AgentContext ctx, OutlineReq input, int maxTokens, int attempt) {
        String system = buildSystem(input.memoryBlock());
        String user = buildUser(input);
        TextChatRequest req = new TextChatRequest(system, user, true, maxTokens);
        log.info("[skeleton-agent] 调用模型 attempt={}/{} episodeId={} maxTokens={} 等待模型返回…",
                attempt, INTERNAL_RETRIES, ctx.episodeId(), maxTokens);
        try {
            TextChatResult resp = gateway.chat(input.seriesId(), req);
            SkeletonDoc doc = AgentTruncationSupport.parseJson(objectMapper, resp.getContent(), SkeletonDoc.class);
            boolean truncated = AgentTruncationSupport.truncatedByLength(resp.getFinishReason());
            if (doc == null) {
                log.warn("[skeleton-agent] 输出不符合 schema seriesId={} attempt={}/{} finishReason={} "
                                + "truncated={} contentLength={} maxTokens={} contentHead={}",
                        input.seriesId(), attempt, INTERNAL_RETRIES, resp.getFinishReason(), truncated,
                        resp.getContent() == null ? 0 : resp.getContent().length(), maxTokens,
                        AgentTruncationSupport.headOf(resp.getContent()));
                return Outcome.failed(truncated, reasonOf(resp, truncated, maxTokens),
                        resp.getPromptTokens(), resp.getCompletionTokens());
            }
            return Outcome.ok(doc, resp.getPromptTokens(), resp.getCompletionTokens());
        } catch (BizException e) {
            log.warn("[skeleton-agent] LLM 业务异常 seriesId={} attempt={} msg={}",
                    input.seriesId(), attempt, e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        } catch (RuntimeException e) {
            log.warn("[skeleton-agent] LLM 异常 seriesId={} attempt={} class={} msg={}",
                    input.seriesId(), attempt, e.getClass().getSimpleName(), e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        }
    }

    /**
     * 失败原因：区分「模型没给合法 JSON」「被 max_tokens 截断」，
     * 并在输出预算被上下文窗口砍掉时把砍剩多少带出来
     * （模型配置的上下文长度偏小是最常见的一种）。
     */
    private String reasonOf(TextChatResult resp, boolean truncated, int requestedTokens) {
        String reason = AgentTruncationSupport.invalidReason(resp, truncated);
        Integer budget = resp.getOutputBudget();
        if (budget != null && budget < requestedTokens) {
            reason = reason + "；这次最多只能让模型写 " + budget
                    + " 个字，可能不够写完，可以在基础信息里把该文本模型的上下文长度调大";
        }
        return reason;
    }

    private String buildSystem(String memoryBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptResolver.resolve("common")).append("\n\n");
        sb.append(promptResolver.resolve("schema_skeleton"));
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            sb.append("\n\n").append(memoryBlock);
        }
        return sb.toString();
    }

    private String buildUser(OutlineReq input) {
        StringBuilder sb = new StringBuilder();
        sb.append("请把以下原文生成分集骨架（");
        if (input.targetEpisodes() != null) {
            sb.append("目标 ").append(input.targetEpisodes()).append(" 集");
        } else {
            sb.append("集数自定");
        }
        sb.append("）：\n\n").append(input.raw());
        return sb.toString();
    }

    /** 一次请求的结果：文档（解析失败为 null）、是否被截断、失败原因、token 消耗。 */
    private record Outcome(SkeletonDoc doc, boolean truncated, String reason,
                           long promptTokens, long completionTokens) {

        static Outcome ok(SkeletonDoc doc, long promptTokens, long completionTokens) {
            return new Outcome(doc, false, "", promptTokens, completionTokens);
        }

        static Outcome failed(boolean truncated, String reason, long promptTokens, long completionTokens) {
            return new Outcome(null, truncated, reason, promptTokens, completionTokens);
        }
    }
}
