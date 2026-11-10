package cn.aigc.dit.server.config;

import cn.aigc.dit.server.drama.DramaStyles;
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
                    video_generate_mode     VARCHAR(16)  NOT NULL DEFAULT 'i2v',
                    video_chain_prev_tail   BOOLEAN      NOT NULL DEFAULT FALSE,
                    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT ck_drama_series_aspect CHECK (aspect_ratio IN ('21:9', '16:9', '4:3', '1:1', '3:4', '9:16')),
                    CONSTRAINT ck_drama_series_resolution CHECK (default_resolution IN ('480P', '720P', '1080P', '2K')),
                    CONSTRAINT ck_drama_series_duration CHECK (default_duration_sec >= 5 AND default_duration_sec <= 15),
                    CONSTRAINT ck_drama_series_planned CHECK (planned_episode_count >= 1 AND planned_episode_count <= 99)
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS idx_drama_series_user ON drama_series (user_id, updated_at DESC)");
        // 已建库的 CREATE TABLE IF NOT EXISTS 不会改旧约束；与 V61 保持同一套取值。
        statement.execute("ALTER TABLE drama_series DROP CONSTRAINT IF EXISTS ck_drama_series_aspect");
        statement.execute("""
                ALTER TABLE drama_series
                ADD CONSTRAINT ck_drama_series_aspect
                CHECK (aspect_ratio IN ('21:9', '16:9', '4:3', '1:1', '3:4', '9:16'))
                """);
        statement.execute("ALTER TABLE drama_series DROP CONSTRAINT IF EXISTS ck_drama_series_resolution");
        statement.execute("""
                ALTER TABLE drama_series
                ADD CONSTRAINT ck_drama_series_resolution
                CHECK (default_resolution IN ('480P', '720P', '1080P', '2K'))
                """);
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
                    script_analyzing_heartbeat_at TIMESTAMPTZ,
                    script_analyze_error        TEXT,
                    storyboard_draft_error      TEXT,
                    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_drama_episode_series_num UNIQUE (series_id, episode_number),
                    CONSTRAINT ck_drama_episode_number CHECK (episode_number >= 1),
                    CONSTRAINT ck_drama_episode_status CHECK (status IN ('未开始', '待确认', '已锁定', '已过期')),
                    CONSTRAINT ck_drama_episode_engine CHECK (engine IN ('pending', 'rules', 'llm', 'v2-agent'))
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
                IS '绑定的文本模型（ai_model.id，category=text）；空表示未绑定，剧本解析/分镜拆分不可用'
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
                IS '分场引擎：pending 尚未用文本模型成功解析；rules 仅兼容旧数据；llm 文本模型；v2-agent 深度解析'
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
                    camera_motion           TEXT,
                    ambiance_audio          TEXT,
                    shot_beats              JSONB,
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
                    video_tail_key          VARCHAR(512),
                    first_frame_key         VARCHAR(512),
                    first_frame_origin      VARCHAR(16),
                    end_frame_key           VARCHAR(512),
                    end_frame_origin        VARCHAR(16),
                    video_generate_mode     VARCHAR(16),
                    video_chain_prev_tail   BOOLEAN,
                    video_generated_mode    VARCHAR(16),
                    chained_from_shot_id    BIGINT,
                    chained_from_tail_key   VARCHAR(512),
                    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_drama_shot_episode_index UNIQUE (episode_id, shot_index),
                    CONSTRAINT ck_drama_shot_index CHECK (shot_index >= 1),
                    CONSTRAINT ck_drama_shot_duration CHECK (duration_sec >= 5 AND duration_sec <= 15),
                    CONSTRAINT ck_drama_shot_cast CHECK (cast_count >= 0),
                    CONSTRAINT ck_drama_shot_status CHECK (status IN ('未开始', '待确认', '已锁定', '已过期')),
                    CONSTRAINT ck_drama_shot_video_mode CHECK (video_generate_mode IS NULL OR video_generate_mode IN ('r2v', 'i2v'))
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
        statement.execute("""
                ALTER TABLE drama_series
                ADD COLUMN IF NOT EXISTS video_generate_mode VARCHAR(16) NOT NULL DEFAULT 'i2v'
                """);
        statement.execute("ALTER TABLE drama_series ALTER COLUMN video_generate_mode SET DEFAULT 'i2v'");
        statement.execute("""
                ALTER TABLE drama_series
                ADD COLUMN IF NOT EXISTS video_chain_prev_tail BOOLEAN NOT NULL DEFAULT FALSE
                """);
        statement.execute("ALTER TABLE drama_series DROP CONSTRAINT IF EXISTS ck_drama_series_video_mode");
        statement.execute("""
                ALTER TABLE drama_series
                ADD CONSTRAINT ck_drama_series_video_mode
                CHECK (video_generate_mode IN ('r2v', 'i2v'))
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_series.video_generate_mode
                IS '分镜出片默认方式：r2v 参考图 / i2v 首尾帧；单镜可覆盖'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_series.video_chain_prev_tail
                IS '默认参考图模式下是否把上一镜落幅当作额外 reference_image；单镜可覆盖'
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS video_tail_key VARCHAR(512)
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS first_frame_key VARCHAR(512)
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS first_frame_origin VARCHAR(16)
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS end_frame_key VARCHAR(512)
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS end_frame_origin VARCHAR(16)
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS video_generate_mode VARCHAR(16)
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS video_chain_prev_tail BOOLEAN
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS video_generated_mode VARCHAR(16)
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS chained_from_shot_id BIGINT
                """);
        statement.execute("""
                ALTER TABLE drama_shot
                ADD COLUMN IF NOT EXISTS chained_from_tail_key VARCHAR(512)
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.video_tail_key
                IS '成片落幅（ffmpeg 抽出的 tail.webp）；同场下一镜 i2v 首帧 / r2v 衔接参考'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.first_frame_key
                IS '开场静帧对象 key：自动 i2i 或用户上传；场内第一镜/换特写时覆盖链式落幅'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.first_frame_origin
                IS '开场静帧来源：auto 自动 i2i / upload 用户上传 / asset 素材库 / prev-end 上一镜尾帧 / prev-tail 上一镜落幅'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.end_frame_key
                IS '用户设置的尾帧对象 key：本地上传或素材库图片'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.end_frame_origin
                IS '尾帧来源：upload 本地上传 / asset 素材库图片'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.video_generate_mode
                IS '本镜出片方式：r2v 参考图 / i2v 首尾帧；空按参考图'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.video_chain_prev_tail
                IS '本镜参考图是否把上一镜落幅当作额外 reference_image；空或不勾选则不衔接'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.video_generated_mode
                IS '最近一次成功出片时的生成方式（r2v / i2v）'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.chained_from_shot_id
                IS '首尾帧出片时使用的上一镜 id'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.chained_from_tail_key
                IS '首尾帧出片时使用的上一镜落幅 key，用于判断上一镜是否已更新'
                """);
        statement.execute("ALTER TABLE drama_shot ADD COLUMN IF NOT EXISTS camera_motion TEXT");
        statement.execute("ALTER TABLE drama_shot ADD COLUMN IF NOT EXISTS ambiance_audio TEXT");
        statement.execute("ALTER TABLE drama_shot ADD COLUMN IF NOT EXISTS shot_beats JSONB");
        statement.execute("""
                COMMENT ON COLUMN drama_shot.shot_beats
                IS '分镜内部镜头序列：起始站位 blocking，以及 1–3 个镜头 beats（景别、动作、连续性、本镜头台词）；空则仍按单句编译'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.camera_motion
                IS '景别与运镜，编译进视频提示词的 Camera 行'
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_shot.ambiance_audio
                IS '画内环境声，编译进视频提示词的 Ambiance 行'
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
        statement.execute("ALTER TABLE drama_shot DROP CONSTRAINT IF EXISTS ck_drama_shot_video_mode");
        statement.execute("""
                ALTER TABLE drama_shot
                ADD CONSTRAINT ck_drama_shot_video_mode
                CHECK (video_generate_mode IS NULL OR video_generate_mode IN ('r2v', 'i2v'))
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
        statement.execute("""
                ALTER TABLE drama_episode
                ADD COLUMN IF NOT EXISTS script_analyzing_heartbeat_at TIMESTAMPTZ
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_episode.script_analyzing_heartbeat_at
                IS '一键分析剧本的心跳；分析期间每分钟续期。超时视为僵死可重试'
                """);
        statement.execute("ALTER TABLE drama_episode DROP CONSTRAINT IF EXISTS ck_drama_episode_engine");
        statement.execute("""
                ALTER TABLE drama_episode
                ADD CONSTRAINT ck_drama_episode_engine
                CHECK (engine IN ('pending', 'rules', 'llm', 'v2-agent'))
                """);
        statement.execute("""
                ALTER TABLE drama_episode
                ADD COLUMN IF NOT EXISTS script_analyze_error TEXT
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_episode.script_analyze_error
                IS '最近一次剧本分析失败原因；成功或重新开始分析时清空'
                """);
        statement.execute("""
                ALTER TABLE drama_episode
                ADD COLUMN IF NOT EXISTS storyboard_draft_error TEXT
                """);
        statement.execute("""
                COMMENT ON COLUMN drama_episode.storyboard_draft_error
                IS '最近一次分镜拆分失败原因；成功或重新开始拆镜时清空'
                """);
        statement.execute("""
                UPDATE drama_series
                SET style_prefix = '真人写实风，真人实拍写真摄影，棚拍柔光与自然肤质，清晰五官与独立发丝，真实布料与妆造质感，浅景深，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿',
                    updated_at = NOW()
                WHERE style_key = 'live-realism'
                """);
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(
                        image_prompt,
                        '高精度二次元写实，半写实3D渲染，精致CG质感，3D国漫轻写实厚涂，棚拍柔光，写实皮肤毛发与布料质感，不要卡通平涂线稿',
                        '真人写实风，真人实拍写真摄影，棚拍柔光与自然肤质，清晰五官与独立发丝，真实布料与妆造质感，浅景深，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿'),
                    description = REPLACE(
                        description,
                        '高精度二次元写实，半写实3D渲染，精致CG质感，3D国漫轻写实厚涂，棚拍柔光，写实皮肤毛发与布料质感，不要卡通平涂线稿',
                        '真人写实风，真人实拍写真摄影，棚拍柔光与自然肤质，清晰五官与独立发丝，真实布料与妆造质感，浅景深，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%高精度二次元写实，半写实3D渲染%'
                   OR COALESCE(description, '') LIKE '%高精度二次元写实，半写实3D渲染%'
                """);
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(
                        image_prompt,
                        '半写实 CG 角色设定立绘、清晰五官与服装材质，3D国漫轻写实厚涂，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，非摄影棚证件照、非真人照片质感。',
                        '真人写真定妆立绘，真实相机棚拍柔光，清晰五官与服装材质，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，禁止二次元卡通与3D国漫厚涂。'),
                    description = REPLACE(
                        description,
                        '半写实 CG 角色设定立绘、清晰五官与服装材质，3D国漫轻写实厚涂，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，非摄影棚证件照、非真人照片质感。',
                        '真人写真定妆立绘，真实相机棚拍柔光，清晰五官与服装材质，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，禁止二次元卡通与3D国漫厚涂。'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%半写实 CG 角色设定立绘%'
                   OR COALESCE(description, '') LIKE '%半写实 CG 角色设定立绘%'
                """);
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(
                        image_prompt,
                        '真人写实风，当代都市实拍，现代常服，电影级摄影，自然肤质与真实光影，浅景深，胶片质感，不要古装，不要武侠仙侠，不要卡通漫画线稿',
                        '真人写实风，真人实拍写真摄影，棚拍柔光与自然肤质，清晰五官与独立发丝，真实布料与妆造质感，浅景深，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿'),
                    description = REPLACE(
                        description,
                        '真人写实风，当代都市实拍，现代常服，电影级摄影，自然肤质与真实光影，浅景深，胶片质感，不要古装，不要武侠仙侠，不要卡通漫画线稿',
                        '真人写实风，真人实拍写真摄影，棚拍柔光与自然肤质，清晰五官与独立发丝，真实布料与妆造质感，浅景深，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%真人写实风，当代都市实拍%'
                   OR COALESCE(description, '') LIKE '%真人写实风，当代都市实拍%'
                """);
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(
                        image_prompt,
                        '电影级三维渲染，次世代数字人质感，皮肤次表面散射，电影布光与浅景深，胶片调色，当代都市常服，不要卡通漫画线稿，不要古装，不要武侠仙侠',
                        '真人写实风，真人实拍写真摄影，棚拍柔光与自然肤质，清晰五官与独立发丝，真实布料与妆造质感，浅景深，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿'),
                    description = REPLACE(
                        description,
                        '电影级三维渲染，次世代数字人质感，皮肤次表面散射，电影布光与浅景深，胶片调色，当代都市常服，不要卡通漫画线稿，不要古装，不要武侠仙侠',
                        '真人写实风，真人实拍写真摄影，棚拍柔光与自然肤质，清晰五官与独立发丝，真实布料与妆造质感，浅景深，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%次世代数字人质感%'
                   OR COALESCE(description, '') LIKE '%次世代数字人质感%'
                """);
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(image_prompt, '当代都市人物，穿现代常服', '当代都市角色，穿现代常服'),
                    description = REPLACE(description, '当代都市人物，穿现代常服', '当代都市角色，穿现代常服'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%当代都市人物，穿现代常服%'
                   OR COALESCE(description, '') LIKE '%当代都市人物，穿现代常服%'
                """);
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(image_prompt, '当代都市数字人，穿现代常服，三维角色渲染', '当代都市角色，穿现代常服'),
                    description = REPLACE(description, '当代都市数字人，穿现代常服，三维角色渲染', '当代都市角色，穿现代常服'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%当代都市数字人，穿现代常服%'
                   OR COALESCE(description, '') LIKE '%当代都市数字人，穿现代常服%'
                """);
        statement.execute("""
                UPDATE drama_series
                SET style_prefix = REPLACE(
                        style_prefix,
                        '当代都市写实常服与日常面料，不要洛丽塔，不要哥特，不要汉服古装，不要卡通平涂线稿',
                        '写实皮肤毛发与布料质感，不要卡通平涂线稿'),
                    updated_at = NOW()
                WHERE style_key = 'live-realism'
                  AND COALESCE(style_prefix, '') LIKE '%当代都市写实常服与日常面料%'
                """);
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(
                        image_prompt,
                        '当代都市写实常服与日常面料，不要洛丽塔，不要哥特，不要汉服古装，不要卡通平涂线稿',
                        '写实皮肤毛发与布料质感，不要卡通平涂线稿'),
                    description = REPLACE(
                        description,
                        '当代都市写实常服与日常面料，不要洛丽塔，不要哥特，不要汉服古装，不要卡通平涂线稿',
                        '写实皮肤毛发与布料质感，不要卡通平涂线稿'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%当代都市写实常服与日常面料%'
                   OR COALESCE(description, '') LIKE '%当代都市写实常服与日常面料%'
                """);
        String modernRealismPrefix = DramaStyles.require(DramaStyles.LIVE_REALISM).prefix().replace("'", "''");
        // 旧「真人3D风」已合并到「现代写实风」：把 drama_series.style_key 改写为 live-realism，
        // 并把陈旧的影视级仿真人数字人/CGI/次世代数字人 提示词刷成现代写实风的新版本。
        // drama_asset 没有 style_key 列（style_key 只属于 drama_series），生成时按 series 的
        // style_key 重新拼 prompt，资产行不需要刷 key。
        statement.execute("""
                UPDATE drama_series
                SET style_key = 'live-realism',
                    style_prefix = '%s',
                    updated_at = NOW()
                WHERE style_key = 'realistic-3d'
                  AND COALESCE(style_prefix, '') NOT LIKE '%%电影级仿真人数字人%%'
                  AND COALESCE(style_prefix, '') NOT LIKE '%%极致写实实拍摄影%%'
                """.formatted(modernRealismPrefix));
        statement.execute("""
                UPDATE drama_series
                SET style_key = 'live-realism',
                    style_prefix = '%s',
                    updated_at = NOW()
                WHERE style_key = 'realistic-3d'
                  AND (COALESCE(style_prefix, '') LIKE '%%电影级仿真人数字人%%'
                    OR COALESCE(style_prefix, '') LIKE '%%photorealistic CGI%%')
                """.formatted(modernRealismPrefix));
        statement.execute("""
                UPDATE drama_series
                SET style_key = 'live-realism',
                    updated_at = NOW()
                WHERE style_key = 'realistic-3d'
                """);
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(
                        image_prompt,
                        '真人3D风，电影级三维渲染，次世代数字人质感，皮肤次表面散射，电影布光与浅景深，当代都市常服，不要卡通平涂线稿，不要古装，不要武侠仙侠',
                        '%s'),
                    description = REPLACE(
                        description,
                        '真人3D风，电影级三维渲染，次世代数字人质感，皮肤次表面散射，电影布光与浅景深，当代都市常服，不要卡通平涂线稿，不要古装，不要武侠仙侠',
                        '%s'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%%次世代数字人质感，皮肤次表面散射，电影布光与浅景深%%'
                   OR COALESCE(description, '') LIKE '%%次世代数字人质感，皮肤次表面散射，电影布光与浅景深%%'
                """.formatted(modernRealismPrefix, modernRealismPrefix));
        // 同名旧前缀（真人3D 改名前残留的 prefix 文本）也一并刷新。
        statement.execute("""
                UPDATE drama_asset
                SET image_prompt = REPLACE(
                        image_prompt,
                        '真人3D风，极致写实实拍摄影，当代电影质感，自然肤质与可见毛孔，真实眼球与独立发丝，窗户光与现场实用光，浅景深，35mm 电影镜头，轻微胶片颗粒，真实布料与空间材质，不要 3D 渲染，不要 CG 数字人，不要游戏角色，不要塑料皮肤，不要二次元卡通，不要无影棚拍大平光，不要古装，不要武侠仙侠',
                        '%s'),
                    description = REPLACE(
                        description,
                        '真人3D风，极致写实实拍摄影，当代电影质感，自然肤质与可见毛孔，真实眼球与独立发丝，窗户光与现场实用光，浅景深，35mm 电影镜头，轻微胶片颗粒，真实布料与空间材质，不要 3D 渲染，不要 CG 数字人，不要游戏角色，不要塑料皮肤，不要二次元卡通，不要无影棚拍大平光，不要古装，不要武侠仙侠',
                        '%s'),
                    updated_at = NOW()
                WHERE COALESCE(image_prompt, '') LIKE '%%真人3D风，极致写实实拍摄影%%'
                   OR COALESCE(description, '') LIKE '%%真人3D风，极致写实实拍摄影%%'
                """.formatted(modernRealismPrefix, modernRealismPrefix));
    }
}
