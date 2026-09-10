package cn.aigc.dit.server.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V1 已执行过的库不会重跑脚本。启动时幂等补 adapter_key 与漫剧表，避免 checksum 变更导致无法启动。
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy adapterKeyPatch() {
        return (Flyway flyway) -> {
            flyway.migrate();
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE ai_model ADD COLUMN IF NOT EXISTS adapter_key VARCHAR(64)");
                statement.execute("""
                        COMMENT ON COLUMN ai_model.adapter_key
                        IS '适配协议（出站翻译包）：gpt-image-2 / seedance-2 / minimax-h3 / agnes-image-2.5 / agnes-video-2.5；mock 为占位'
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'minimax-h3', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND category = 'video'
                          AND (provider ILIKE '%comfy%' OR model_key ILIKE '%minimax%')
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'seedance-2', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND category = 'video'
                          AND (provider ILIKE '%running%' OR model_key ILIKE '%seedance%')
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'gpt-image-2', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND category = 'image'
                          AND (provider ILIKE '%running%' OR model_key ILIKE '%gpt-image%')
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'mock', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND (provider ILIKE 'mock' OR model_key ILIKE 'mock%')
                        """);
                statement.execute("""
                        UPDATE ai_model SET provider = 'ComfyUI', updated_at = NOW()
                        WHERE adapter_key = 'minimax-h3' AND provider IS DISTINCT FROM 'ComfyUI'
                        """);
                statement.execute("""
                        UPDATE ai_model SET provider = 'RunningHub', updated_at = NOW()
                        WHERE adapter_key IN ('gpt-image-2', 'seedance-2') AND provider IS DISTINCT FROM 'RunningHub'
                        """);
                statement.execute("""
                        UPDATE ai_model SET provider = '官方API', updated_at = NOW()
                        WHERE adapter_key IN ('agnes-image-2.5', 'agnes-video-2.5') AND provider IS DISTINCT FROM '官方API'
                        """);
                statement.execute("""
                        UPDATE ai_model SET provider = 'mock', updated_at = NOW()
                        WHERE adapter_key = 'mock' AND provider IS DISTINCT FROM 'mock'
                        """);
                ensureDramaTables(statement);
            } catch (SQLException e) {
                throw new IllegalStateException("补齐 ai_model.adapter_key / 漫剧表失败", e);
            }
        };
    }

    /** 已跑过旧 V1 的库不会重跑迁移，启动时幂等建漫剧表。 */
    static void ensureDramaTables(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS drama_series (
                    id                      BIGSERIAL PRIMARY KEY,
                    user_id                 BIGINT       NOT NULL REFERENCES sys_user (id),
                    name                    VARCHAR(128) NOT NULL,
                    style_key               VARCHAR(64)  NOT NULL DEFAULT 'anime-comic',
                    style_prefix            VARCHAR(512),
                    aspect_ratio            VARCHAR(16)  NOT NULL DEFAULT '16:9',
                    default_resolution      VARCHAR(16)  NOT NULL DEFAULT '720P',
                    default_duration_sec    INTEGER      NOT NULL DEFAULT 5,
                    planned_episode_count   INTEGER      NOT NULL DEFAULT 1,
                    text_model_id           BIGINT       REFERENCES ai_model (id),
                    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT ck_drama_series_aspect CHECK (aspect_ratio IN ('16:9', '9:16', '1:1')),
                    CONSTRAINT ck_drama_series_resolution CHECK (default_resolution IN ('480P', '720P')),
                    CONSTRAINT ck_drama_series_duration CHECK (default_duration_sec IN (5, 10, 15)),
                    CONSTRAINT ck_drama_series_planned CHECK (planned_episode_count >= 1 AND planned_episode_count <= 99)
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_series_user ON drama_series (user_id, updated_at DESC)");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS drama_episode (
                    id                          BIGSERIAL PRIMARY KEY,
                    series_id                   BIGINT       NOT NULL REFERENCES drama_series (id) ON DELETE CASCADE,
                    user_id                     BIGINT       NOT NULL REFERENCES sys_user (id),
                    episode_number              INTEGER      NOT NULL,
                    name                        VARCHAR(128) NOT NULL,
                    source_text                 TEXT,
                    script                      JSONB,
                    engine                      VARCHAR(16)  NOT NULL DEFAULT 'pending',
                    status                      VARCHAR(16)  NOT NULL DEFAULT '未开始',
                    inherited_from_episode_id   BIGINT       REFERENCES drama_episode (id) ON DELETE SET NULL,
                    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_drama_episode_series_num UNIQUE (series_id, episode_number),
                    CONSTRAINT ck_drama_episode_number CHECK (episode_number >= 1),
                    CONSTRAINT ck_drama_episode_status CHECK (status IN ('未开始', '待确认', '已锁定', '已过期')),
                    CONSTRAINT ck_drama_episode_engine CHECK (engine IN ('pending', 'rules', 'llm'))
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_episode_series ON drama_episode (series_id, episode_number)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_episode_user ON drama_episode (user_id, updated_at DESC)");
        statement.execute("""
                ALTER TABLE drama_series
                ADD COLUMN IF NOT EXISTS style_key VARCHAR(64) NOT NULL DEFAULT 'anime-comic'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_series.style_key
                IS '风格键（如 anime-comic）；UI 用图例卡片选择，不展示前缀文本'
                """);
        statement.execute("""
                UPDATE drama_series
                SET style_key = 'anime-comic', updated_at = NOW()
                WHERE style_key IS NULL OR style_key = ''
                """);
        statement.execute("""
                UPDATE drama_series
                SET style_prefix = '二次元漫画风，干净线稿，赛璐璐平涂，清晰分色，电影分镜构图，不要写实摄影质感',
                    updated_at = NOW()
                WHERE style_key = 'anime-comic'
                  AND (style_prefix IS NULL OR style_prefix = '')
                """);
        statement.execute("""
                ALTER TABLE drama_series
                ADD COLUMN IF NOT EXISTS text_model_id BIGINT REFERENCES ai_model (id)
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_series.text_model_id
                IS '绑定的文本模型（ai_model.id，category=text）；空表示未绑定，乙走规则引擎'
                """);
        statement.execute("""
                UPDATE ai_model SET provider = '官方API', updated_at = NOW()
                WHERE adapter_key = 'agnes-text' AND provider IS DISTINCT FROM '官方API'
                """);
        statement.execute("""
                UPDATE ai_model SET provider = '自定义配置', updated_at = NOW()
                WHERE adapter_key = 'openai-compat' AND provider IS DISTINCT FROM '自定义配置'
                """);
        statement.execute("""
                ALTER TABLE drama_episode
                ADD COLUMN IF NOT EXISTS engine VARCHAR(16) NOT NULL DEFAULT 'pending'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_episode.engine
                IS '分场引擎：pending 乙未合入；rules 规则分场；llm 文本模型'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_episode.script
                IS '分场剧本文档 JSON（ScriptDoc）；阶段 3 由 DramaTextService 写入，乙未合入时可空'
                """);
    }
}
