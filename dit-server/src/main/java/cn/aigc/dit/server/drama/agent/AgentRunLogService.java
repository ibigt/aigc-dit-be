package cn.aigc.dit.server.drama.agent;

import cn.aigc.dit.server.drama.entity.AgentRun;
import cn.aigc.dit.server.drama.mapper.AgentRunMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * Agent 运行日志服务。每次 Agent / Supervisor 跑完后调用 record() 落 agent_run 表。
 *
 * 用法（由 DramaAnalysisOrchestrator.runAgent 统一调用）：
 *   runLog.record("skeleton", ctx, result);
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunLogService {

    private final AgentRunMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 记录一次 Agent 调用结果。
     */
    public void record(String agentType, AgentContext ctx, AgentResult<?> result) {
        try {
            AgentRun row = new AgentRun();
            row.setSeriesId(ctx.seriesId());
            row.setEpisodeId(ctx.episodeId());
            row.setAgentType(agentType);
            row.setSessionId(ctx.sessionId());
            row.setModel(ctx.model());
            row.setSuccess(result.success());
            row.setSupervisorRepaired(result.supervisorRepaired());
            row.setRetryCount(result.retryCount());
            row.setPromptTokens(toInt(result.promptTokens()));
            row.setCompletionTokens(toInt(result.completionTokens()));
            row.setError(result.error());

            // supervisor 相关信息
            if (!result.supervisorIssues().isEmpty()) {
                row.setSupervisorType(agentType + "_sup");
                // 只有 WARNING 不算不通过（不重跑），别把提醒记成失败。
                row.setSupervisorPass(result.supervisorIssues().stream().noneMatch(SupervisorIssue::isError));
                row.setSupervisorIssues(toJson(result.supervisorIssues()));
            } else if (result.supervisorRepaired()) {
                row.setSupervisorPass(true);
            }

            row.setStartedAt(OffsetDateTime.now());
            row.setFinishedAt(OffsetDateTime.now());
            row.setDurationMs(0);  // 真实耗时由调用方在 execute 外层计算后回填
            mapper.insert(row);
        } catch (Exception e) {
            // 可观测层失败不影响主流程
            log.warn("[agent-run-log] 记录失败 agentType={} episodeId={} error={}",
                    agentType, ctx.episodeId(), e.getMessage());
        }
    }

    /**
     * 记录 Supervisor 单独跑的结果（仅在 supervisor 跑了 LLM 兜底时调用）。
     */
    public void recordSupervisor(String agentType, AgentContext ctx,
                                  boolean pass, List<SupervisorIssue> issues) {
        try {
            AgentRun row = new AgentRun();
            row.setSeriesId(ctx.seriesId());
            row.setEpisodeId(ctx.episodeId());
            row.setAgentType(agentType);
            row.setSupervisorType(agentType + "_sup_llm");
            row.setSessionId(ctx.sessionId());
            row.setModel(ctx.model());
            row.setSuccess(true);
            row.setSupervisorPass(pass);
            row.setRetryCount(0);
            row.setSupervisorIssues(issues == null ? null : toJson(issues));
            row.setStartedAt(OffsetDateTime.now());
            row.setFinishedAt(OffsetDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("[agent-run-log] supervisor 记录失败 agentType={} error={}", agentType, e.getMessage());
        }
    }

    /** 计算字符串 SHA-256（用于 input/output hash 去重分析）。 */
    public static String sha256(String s) {
        if (s == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }

    public static long durationMs(OffsetDateTime start) {
        return Duration.between(start, OffsetDateTime.now()).toMillis();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Integer toInt(long value) {
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) value;
    }
}
