package cn.aigc.dit.server.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V1 已执行过的库不会重跑脚本。启动时幂等补 adapter_key 与短剧表，避免 checksum 变更导致无法启动。
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
                        IS '适配协议（出站翻译包）：gpt-image-2 / seedance-2 / seedance-2.0 / seedance-2.5 / minimax-h3 / agnes-image-2.5 / agnes-video-2.5；mock 为占位'
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
                        SET adapter_key = 'seedance-2.5', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND category = 'video'
                          AND (model_key ILIKE '%seedance-2.5%'
                            OR model_key ILIKE '%seedance-2-5%'
                            OR model_key ILIKE '%doubao-seedance-2-5%'
                            OR model_key ILIKE '%doubao-seedance-2.5%')
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'seedance-2.0', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND category = 'video'
                          AND (model_key ILIKE '%doubao-seedance-2-0%'
                            OR model_key ILIKE '%doubao-seedance-2.0%'
                            OR model_key ILIKE '%seedance-2-0-fast%'
                            OR model_key ILIKE '%seedance-2.0-fast%')
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
                        WHERE adapter_key = 'minimax-h3'
                          AND provider = '官方API'
                          AND (endpoint ILIKE '%8188%'
                            OR endpoint ILIKE '%127.0.0.1%'
                            OR endpoint ILIKE '%localhost%')
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
                throw new IllegalStateException("补齐 ai_model.adapter_key / 短剧表失败", e);
            }
        };
    }

    /** 已跑过旧 V1 的库不会重跑迁移，启动时幂等建短剧表（含 asset / chapter / shot）。 */
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
                    image_model_id          BIGINT       REFERENCES ai_model (id),
                    image_adapter_key       VARCHAR(64),
                    video_model_id          BIGINT       REFERENCES ai_model (id),
                    video_adapter_key       VARCHAR(64),
                    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT ck_drama_series_aspect CHECK (aspect_ratio IN ('16:9', '9:16', '1:1')),
                    CONSTRAINT ck_drama_series_resolution CHECK (default_resolution IN ('480P', '720P')),
                    CONSTRAINT ck_drama_series_duration CHECK (default_duration_sec >= 5 AND default_duration_sec <= 15),
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
                    storyboard_drafting_at      TIMESTAMPTZ,
                    script_analyzing_at         TIMESTAMPTZ,
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
                IS '风格键（anime-comic / ancient-realism / anime-wuxia）；UI 用图例卡片选择，不展示前缀文本'
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
        statement.execute("""
                ALTER TABLE drama_series
                ADD COLUMN IF NOT EXISTS image_model_id BIGINT REFERENCES ai_model (id)
                """);
        statement.execute("""
                ALTER TABLE drama_series
                ADD COLUMN IF NOT EXISTS image_adapter_key VARCHAR(64)
                """);
        statement.execute("""
                ALTER TABLE drama_series
                ADD COLUMN IF NOT EXISTS video_model_id BIGINT REFERENCES ai_model (id)
                """);
        statement.execute("""
                ALTER TABLE drama_series
                ADD COLUMN IF NOT EXISTS video_adapter_key VARCHAR(64)
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_series.image_model_id
                IS '资产页全局图像模型（ai_model.id，category=image）；空则不可 t2i，仍可上传'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_series.image_adapter_key
                IS '绑定图像模型当时的 adapter_key（不要只存 provider）'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_series.video_model_id
                IS '出片页全局视频模型（ai_model.id，category=video）；阶段 6 再绑'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_series.video_adapter_key
                IS '绑定视频模型当时的 adapter_key'
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS drama_asset (
                    id                      BIGSERIAL PRIMARY KEY,
                    series_id               BIGINT       NOT NULL REFERENCES drama_series (id) ON DELETE CASCADE,
                    episode_id              BIGINT       NOT NULL REFERENCES drama_episode (id) ON DELETE CASCADE,
                    user_id                 BIGINT       NOT NULL REFERENCES sys_user (id),
                    name                    VARCHAR(128) NOT NULL,
                    type                    VARCHAR(16)  NOT NULL,
                    state_tag               VARCHAR(64)  NOT NULL DEFAULT 'base',
                    description             TEXT,
                    identity_invariants     TEXT,
                    image_prompt            TEXT,
                    voice_lock              VARCHAR(256),
                    voice_reference_key     VARCHAR(512),
                    voice_reference_mime    VARCHAR(128),
                    must_split              BOOLEAN      NOT NULL DEFAULT FALSE,
                    needs_human             BOOLEAN      NOT NULL DEFAULT FALSE,
                    status                  VARCHAR(16)  NOT NULL DEFAULT '未开始',
                    identity_version        INTEGER      NOT NULL DEFAULT 0,
                    image_storage_key       VARCHAR(512),
                    image_asset_id          BIGINT       REFERENCES asset (id) ON DELETE SET NULL,
                    gen_task_id             BIGINT       REFERENCES gen_task (id) ON DELETE SET NULL,
                    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT ck_drama_asset_type CHECK (type IN ('角色', '场景', '道具')),
                    CONSTRAINT ck_drama_asset_status CHECK (status IN ('未开始', '待确认', '已锁定', '已过期')),
                    CONSTRAINT ck_drama_asset_identity_version CHECK (identity_version >= 0)
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_asset_episode ON drama_asset (episode_id, type, id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_asset_series ON drama_asset (series_id, updated_at DESC)");
        statement.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_drama_asset_episode_name_state
                ON drama_asset (episode_id, type, name, state_tag)
                """);
        statement.execute("""
                COMMENT ON TABLE drama_asset IS '短剧资产注册表：描述符 + 参考图 + 身份不变量 + 版本号；未锁定不能进分镜参考'
                """);
        statement.execute("""
                ALTER TABLE drama_asset
                ADD COLUMN IF NOT EXISTS voice_reference_key VARCHAR(512)
                """);
        statement.execute("""
                ALTER TABLE drama_asset
                ADD COLUMN IF NOT EXISTS voice_reference_mime VARCHAR(128)
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_asset.voice_reference_key
                IS '角色音色参考音频的对象存储 key'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_asset.voice_reference_mime
                IS '角色音色参考音频 MIME'
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS drama_chapter (
                    id              BIGSERIAL PRIMARY KEY,
                    series_id       BIGINT       NOT NULL REFERENCES drama_series (id) ON DELETE CASCADE,
                    episode_id      BIGINT       NOT NULL REFERENCES drama_episode (id) ON DELETE CASCADE,
                    user_id         BIGINT       NOT NULL REFERENCES sys_user (id),
                    chapter_index   INTEGER      NOT NULL,
                    name            VARCHAR(128) NOT NULL,
                    scene_index     INTEGER      NOT NULL,
                    body            TEXT,
                    status          VARCHAR(16)  NOT NULL DEFAULT '待确认',
                    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_drama_chapter_episode_index UNIQUE (episode_id, chapter_index),
                    CONSTRAINT ck_drama_chapter_index CHECK (chapter_index >= 1),
                    CONSTRAINT ck_drama_chapter_scene CHECK (scene_index >= 1),
                    CONSTRAINT ck_drama_chapter_status CHECK (status IN ('未开始', '待确认', '已锁定', '已过期'))
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_chapter_episode ON drama_chapter (episode_id, chapter_index)");
        statement.execute("""
                COMMENT ON TABLE drama_chapter IS '短剧章节（按场拆章）；正文来自分场剧本'
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS drama_shot (
                    id                      BIGSERIAL PRIMARY KEY,
                    series_id               BIGINT       NOT NULL REFERENCES drama_series (id) ON DELETE CASCADE,
                    episode_id              BIGINT       NOT NULL REFERENCES drama_episode (id) ON DELETE CASCADE,
                    chapter_id              BIGINT       NOT NULL REFERENCES drama_chapter (id) ON DELETE CASCADE,
                    user_id                 BIGINT       NOT NULL REFERENCES sys_user (id),
                    shot_index              INTEGER      NOT NULL,
                    title                   VARCHAR(128),
                    duration_sec            INTEGER      NOT NULL DEFAULT 5,
                    narrative_goal          TEXT,
                    scene                   TEXT,
                    dialogue                TEXT,
                    continuity_in           VARCHAR(256),
                    continuity_out          VARCHAR(256),
                    reference_asset_ids     JSONB        NOT NULL DEFAULT '[]'::jsonb,
                    character_names         JSONB        NOT NULL DEFAULT '[]'::jsonb,
                    prop_names              JSONB        NOT NULL DEFAULT '[]'::jsonb,
                    location_name           VARCHAR(128),
                    cast_count              INTEGER      NOT NULL DEFAULT 0,
                    compiled_prompt         TEXT,
                    compiled_fingerprint    VARCHAR(128),
                    prompt_override         VARCHAR(4000),
                    status                  VARCHAR(16)  NOT NULL DEFAULT '待确认',
                    gen_task_id             BIGINT       REFERENCES gen_task (id) ON DELETE SET NULL,
                    video_key               VARCHAR(512),
                    video_poster_key        VARCHAR(512),
                    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_drama_shot_episode_index UNIQUE (episode_id, shot_index),
                    CONSTRAINT ck_drama_shot_index CHECK (shot_index >= 1),
                    CONSTRAINT ck_drama_shot_duration CHECK (duration_sec >= 5 AND duration_sec <= 15),
                    CONSTRAINT ck_drama_shot_cast CHECK (cast_count >= 0),
                    CONSTRAINT ck_drama_shot_status CHECK (status IN ('未开始', '待确认', '已锁定', '已过期'))
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_shot_chapter ON drama_shot (chapter_id, shot_index)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_shot_episode ON drama_shot (episode_id, shot_index)");
        statement.execute("""
                COMMENT ON TABLE drama_shot IS '短剧分镜；视频提示词由编译器注入 Style Prefix / 身份不变量 / Picture 序号，用户不手写长 prompt'
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS prompt_override VARCHAR(4000)
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.prompt_override
                IS '用户手动覆盖的视频提示词；非空时优先于 compiled_prompt 出片'
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS video_poster_key VARCHAR(512)
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.video_poster_key
                IS '成片首帧（ffmpeg 抽出的 poster.webp）；底片条预览用，未出片为空'
                """);
        statement.execute("ALTER TABLE drama_series DROP CONSTRAINT IF EXISTS ck_drama_series_duration");
        statement.execute("""
                ALTER TABLE drama_series
                ADD CONSTRAINT ck_drama_series_duration
                CHECK (default_duration_sec >= 5 AND default_duration_sec <= 15)
                """);
        statement.execute("ALTER TABLE drama_shot DROP CONSTRAINT IF EXISTS ck_drama_shot_duration");
        statement.execute("""
                ALTER TABLE drama_shot
                ADD CONSTRAINT ck_drama_shot_duration
                CHECK (duration_sec >= 5 AND duration_sec <= 15)
                """);
        statement.execute("""
                ALTER TABLE drama_episode
                ADD COLUMN IF NOT EXISTS storyboard_drafting_at TIMESTAMPTZ
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_episode.storyboard_drafting_at
                IS '正在按场拆镜的开始时间；空表示空闲。超时视为僵死可重试'
                """);
        statement.execute("""
                ALTER TABLE drama_episode
                ADD COLUMN IF NOT EXISTS script_analyzing_at TIMESTAMPTZ
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_episode.script_analyzing_at
                IS '正在一键分析剧本的开始时间；空表示空闲。超时视为僵死可重试'
                """);
    }
}
