package cn.aigc.dit.server.drama.entity;

import cn.aigc.dit.server.drama.annotation.IndexHint;
import cn.aigc.dit.server.mybatis.JsonbTextTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Agent 运行日志（V36）。
 * 用于可观测、A/B 测试、问题归因。
 *
 * 索引说明（由 Flyway V36__agent_run.sql 创建）：
 * <ul>
 *   <li>idx_agent_run_episode（episode_id）</li>
 *   <li>idx_agent_run_session（session_id）</li>
 *   <li>idx_agent_run_agent_type（agent_type, success）</li>
 *   <li>idx_agent_run_prompt_version（prompt_key, prompt_version）</li>
 * </ul>
 *
 * MP-Plus 3.5.7 还没有 {@code @TableIndex}（3.5.9+ 才引入），用 {@link IndexHint}
 * 在编译期做对账提示；真正的 DDL 由 Flyway 管理，避免重复声明导致 CI 噪音。
 */
@Data
@TableName(value = "agent_run", autoResultMap = true)
@IndexHint(migration = "V36__agent_run.sql",
        reason = "MP 3.5.7 不支持 @TableIndex；4 个索引统一在 V36 迁移里创建")
public class AgentRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long seriesId;
    private Long episodeId;
    private String agentType;
    private String supervisorType;
    private String sessionId;
    private String promptKey;
    private Integer promptVersion;
    private String model;
    private String inputHash;
    private String outputHash;
    private Boolean success;
    private Boolean supervisorPass;
    private Boolean supervisorRepaired;

    @TableField(typeHandler = JsonbTextTypeHandler.class)
    private String supervisorIssues;        // Postgres jsonb 列；存 JSON 文本，读时兼容两种历史写法
    private Integer retryCount;
    private Integer promptTokens;
    private Integer completionTokens;
    private String error;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;
    private Integer durationMs;
}
