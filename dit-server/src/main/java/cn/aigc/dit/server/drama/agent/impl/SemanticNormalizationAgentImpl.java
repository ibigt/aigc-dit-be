package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.DramaEntityGroups;
import cn.aigc.dit.server.drama.DramaErrorText;
import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AgentResult;
import cn.aigc.dit.server.drama.agent.ScriptAgent;
import cn.aigc.dit.server.drama.agent.SemanticNormalizationAgent;
import cn.aigc.dit.server.drama.agent.SemanticNormalizationReq;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.agent.SupervisorAgent;
import cn.aigc.dit.server.drama.agent.SupervisorVerdict;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptPromptResolver;
import cn.aigc.dit.server.drama.text.SemanticNormalizationResult;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.drama.text.TextChatRequest;
import cn.aigc.dit.server.drama.text.TextChatResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One episode-level model decision for aliases, canonical names and scene boundaries. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticNormalizationAgentImpl implements SemanticNormalizationAgent {
    private static final int MAX_TOKENS = 16384;

    /** 截断后允许加倍到的上限（与 v1 老链路的 MAX_JSON_TOKENS 一致）。 */
    private static final int MAX_TOKENS_CEILING = AgentTruncationSupport.MAX_TOKENS_CEILING;

    private static final int INTERNAL_RETRIES = 2;

    private final TextChatGateway gateway;
    private final ScriptPromptResolver promptResolver;
    private final ObjectMapper objectMapper;

    /**
     * 语义归一化。
     *
     * <p>失败阶梯与其它步骤一致：整集一次请求 → 被 max_tokens 截断就加倍预算重放。
     * 这里<b>不</b>像剧本步那样细拆：语义归一化输出的是整集归一化剧本 + 别名分组，
     * 实体别名是跨场次决策，拆成几片各判各的必然判不一致 —— 宁可这一步失败退回原剧本
     * （编排层会 fallback），也不能给出一份自相矛盾的归一化结果。
     */
    @Override
    public AgentResult<SemanticNormalizationResult> execute(AgentContext ctx, SemanticNormalizationReq input) {
        String system = buildSystem(input);
        String user;
        try {
            user = buildUser(input);
        } catch (JsonProcessingException e) {
            return AgentResult.fail("准备归一化输入时出错：" + e.getMessage());
        }
        long promptTokens = 0L;
        long completionTokens = 0L;

        int tokens = MAX_TOKENS;
        Outcome last = null;
        for (int attempt = 1; attempt <= INTERNAL_RETRIES; attempt++) {
            last = requestOnce(ctx, input, system, user, tokens,
                    "attempt=" + attempt + "/" + INTERNAL_RETRIES);
            promptTokens += last.promptTokens();
            completionTokens += last.completionTokens();
            if (last.result() != null) {
                return AgentResult.ok(last.result(), promptTokens, completionTokens);
            }
            if (last.truncated() && tokens < MAX_TOKENS_CEILING) {
                tokens = MAX_TOKENS_CEILING;
                log.warn("[semantic-normalization-agent] 输出被 max_tokens 截断，加倍预算重放 episodeId={} maxTokens={}",
                        ctx.episodeId(), tokens);
            }
        }
        String reason = last == null ? "" : last.reason();
        return AgentResult.fail(DramaErrorText.parseFailed(INTERNAL_RETRIES, reason), INTERNAL_RETRIES);
    }

    /** 发一次请求并解析。 */
    private Outcome requestOnce(AgentContext ctx, SemanticNormalizationReq input, String system, String user,
                                int maxTokens, String stage) {
        log.info("[semantic-normalization-agent] 调用模型 {} episodeId={} maxTokens={} 等待模型返回…",
                stage, ctx.episodeId(), maxTokens);
        try {
            TextChatResult response = gateway.chat(input.seriesId(),
                    new TextChatRequest(system, user, true, maxTokens));
            SemanticNormalizationResult parsed = parse(response.getContent());
            boolean truncated = AgentTruncationSupport.truncatedByLength(response.getFinishReason());
            if (parsed == null) {
                log.warn("[semantic-normalization-agent] JSON 合同解析失败 seriesId={} {} finishReason={} "
                                + "truncated={} contentLength={} maxTokens={} contentHead={}",
                        input.seriesId(), stage, response.getFinishReason(), truncated,
                        response.getContent() == null ? 0 : response.getContent().length(), maxTokens,
                        AgentTruncationSupport.headOf(response.getContent()));
                return Outcome.failed(truncated, AgentTruncationSupport.invalidReason(response, truncated),
                        response.getPromptTokens(), response.getCompletionTokens());
            }
            // 分组结构与「规范名写回剧本」由代码机械收尾：模型只管判「谁和谁是一个东西」，
            // 别名写法由代码统一，免得模型漏改一处就把整步判死、连它判对的部分一起丢掉。
            if (DramaEntityGroups.apply(parsed, input.references())) {
                log.info("[semantic-normalization-agent] 分组已按来源修正，规范名已写回剧本 episodeId={}",
                        ctx.episodeId());
            }
            return Outcome.ok(parsed, response.getPromptTokens(), response.getCompletionTokens());
        } catch (BizException e) {
            log.warn("[semantic-normalization-agent] LLM 业务异常 seriesId={} {} msg={}",
                    input.seriesId(), stage, e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        } catch (RuntimeException e) {
            log.warn("[semantic-normalization-agent] LLM 异常 seriesId={} {} class={} msg={}",
                    input.seriesId(), stage, e.getClass().getSimpleName(), e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        }
    }

    /** The generic contract does not revalidate its repaired output; this integrity-critical stage must. */
    @Override
    public AgentResult<SemanticNormalizationResult> executeWithSupervision(
            AgentContext ctx,
            SemanticNormalizationReq input,
            SupervisorAgent<SemanticNormalizationResult> supervisor) {
        AgentResult<SemanticNormalizationResult> first = execute(ctx, input);
        if (!first.success() || supervisor == null) return first;
        SupervisorVerdict firstVerdict = supervisor.validate(ctx, first.output(), input);
        if (firstVerdict.passed()) {
            log.info("[semantic-normalization-supervisor] 校验通过 episodeId={}", ctx.episodeId());
            return first;
        }
        log.warn("[semantic-normalization-supervisor] 校验不通过 episodeId={} 问题数={}，准备注入反馈修复重跑一次",
                ctx.episodeId(), firstVerdict.issues().size());
        ScriptAgent.logIssues("semantic-normalization", ctx.episodeId(), firstVerdict.issues());

        SemanticNormalizationReq repairedInput = withRepairFeedback(input, firstVerdict.issues());
        AgentResult<SemanticNormalizationResult> repaired = execute(ctx, repairedInput);
        long promptTokens = first.promptTokens() + repaired.promptTokens();
        long completionTokens = first.completionTokens() + repaired.completionTokens();
        if (!repaired.success()) {
            if (!firstVerdict.hasErrors()) {
                // 只有 warning（语义类建议）：修复轮没跑通也保留第一次的结果，不能把这一步判死。
                log.warn("[semantic-normalization-supervisor] 修复轮失败，但首次结果只有告警，保留首次结果 episodeId={}",
                        ctx.episodeId());
                return new AgentResult<>(true, first.output(), null, true, firstVerdict.issues(),
                        first.retryCount() + repaired.retryCount(), 1, promptTokens, completionTokens);
            }
            return new AgentResult<>(false, salvage(first.output(), input), repaired.error(), true,
                    firstVerdict.issues(), first.retryCount() + repaired.retryCount(), 1,
                    promptTokens, completionTokens);
        }
        SupervisorVerdict repairedVerdict = supervisor.validate(ctx, repaired.output(), input);
        if (repairedVerdict.hasErrors()) {
            if (!firstVerdict.hasErrors()) {
                // 修复后冒出硬错误，而第一次只有告警：退回第一次的结果，别拿更差的版本。
                log.warn("[semantic-normalization-supervisor] 修复后出现硬错误，退回首次结果 episodeId={} 问题数={}",
                        ctx.episodeId(), repairedVerdict.issues().size());
                return new AgentResult<>(true, first.output(), null, true, firstVerdict.issues(),
                        first.retryCount() + repaired.retryCount(), 1, promptTokens, completionTokens);
            }
            log.warn("[semantic-normalization-supervisor] 修复后仍不通过 episodeId={} 问题数={}",
                    ctx.episodeId(), repairedVerdict.issues().size());
            ScriptAgent.logIssues("semantic-normalization", ctx.episodeId(), repairedVerdict.issues());
            return new AgentResult<>(false, salvage(repaired.output(), input),
                    "按反馈改写了一次，人物和场景的对应关系仍对不上",
                    true, repairedVerdict.issues(), first.retryCount() + repaired.retryCount(), 1,
                    promptTokens, completionTokens);
        }
        log.info("[semantic-normalization-supervisor] 修复后通过 episodeId={}", ctx.episodeId());
        return new AgentResult<>(true, repaired.output(), null, true, firstVerdict.issues(),
                first.retryCount() + repaired.retryCount(), 1, promptTokens, completionTokens);
    }

    /**
     * 抢救：归一化没过校验时，模型改写出来的剧本不能用，但它判出的<b>别名分组</b>通常还是对的。
     *
     * <p>把分组套回原始剧本（原文是完好的），这样同一件东西至少不会抽出两条资产 ——
     * 14 环境实测（MiniMax / episode 128）里重复资产就是这么来的：归一化被判死 → 回退原始剧本
     * → 原始剧本里的别名写法原样进了资产清单。
     *
     * @return 抢救出来的结果；没有可用的分组时返回 null（交给编排层回退原始剧本）
     */
    private SemanticNormalizationResult salvage(SemanticNormalizationResult modelOutput,
                                                 SemanticNormalizationReq input) {
        if (modelOutput == null || input == null || input.script() == null) return null;
        List<SemanticNormalizationResult.EntityGroup> groups = modelOutput.getEntityGroups();
        if (groups == null || groups.isEmpty()) return null;
        ScriptDoc salvaged = DramaEntityGroups.copyForRewrite(input.script());
        if (!DramaEntityGroups.applyCanonicalNames(salvaged, groups, input.references())) {
            // 没有别名要统一：抢救不出东西来，交给编排层回退
            return null;
        }
        SemanticNormalizationResult result = new SemanticNormalizationResult();
        result.setNormalizedScript(salvaged);
        result.setEntityGroups(groups);
        result.setSceneLineage(modelOutput.getSceneLineage());
        log.info("[semantic-normalization-agent] 归一化未通过校验，沿用模型判出的别名分组统一原始剧本的写法 episodeId={}",
                input.episodeId());
        return result;
    }

    @Override
    public SemanticNormalizationReq withRepairFeedback(SemanticNormalizationReq input,
                                                        List<SupervisorIssue> issues) {
        if (issues == null || issues.isEmpty()) return input;
        StringBuilder feedback = new StringBuilder("上一次输出未通过校验，请按下面列出的问题修复：\n");
        for (SupervisorIssue issue : issues) {
            feedback.append("- ").append(issue.field()).append(": ")
                    .append(issue.message()).append('\n');
            if (issue.suggestion() != null && !issue.suggestion().isBlank()) {
                feedback.append("  处理建议：").append(issue.suggestion()).append('\n');
            }
        }
        return new SemanticNormalizationReq(input.seriesId(), input.episodeId(), input.script(),
                input.references(), input.memoryBlock(), feedback.toString());
    }

    private String buildSystem(SemanticNormalizationReq input) {
        StringBuilder system = new StringBuilder(promptResolver.resolve("schema_semantic_normalization"));
        if (input.memoryBlock() != null && !input.memoryBlock().isBlank()) {
            system.append("\n\n【项目记忆，仅用于理解上下文，不得创建本集没有的实体】\n")
                    .append(input.memoryBlock());
        }
        return system.toString();
    }

    private String buildUser(SemanticNormalizationReq input) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_script", input.script());
        payload.put("source_references", input.references());
        StringBuilder user = new StringBuilder("请对本集执行一次语义归一化，并严格返回合同 JSON。\n\n")
                .append(objectMapper.writeValueAsString(payload));
        if (input.repairFeedback() != null && !input.repairFeedback().isBlank()) {
            user.append("\n\n【完整性修复反馈】\n").append(input.repairFeedback());
        }
        return user.toString();
    }

    private SemanticNormalizationResult parse(String content) {
        return AgentTruncationSupport.parseJson(objectMapper, content, SemanticNormalizationResult.class);
    }

    /** 一次请求的结果。 */
    private record Outcome(SemanticNormalizationResult result, boolean truncated, String reason,
                           long promptTokens, long completionTokens) {
        static Outcome ok(SemanticNormalizationResult result, long promptTokens, long completionTokens) {
            return new Outcome(result, false, null, promptTokens, completionTokens);
        }

        static Outcome failed(boolean truncated, String reason, long promptTokens, long completionTokens) {
            return new Outcome(null, truncated, reason, promptTokens, completionTokens);
        }
    }
}
