-- Some installations used V18-V30 for migrations from another branch. Those
-- versions are already recorded in Flyway history, so the caption and script
-- tables introduced under those numbers would otherwise never be created.
-- All statements are safe on databases where the original migrations ran.

CREATE TABLE IF NOT EXISTS drama_shot_caption (
    id                   BIGSERIAL PRIMARY KEY,
    shot_id              BIGINT       NOT NULL REFERENCES drama_shot (id) ON DELETE CASCADE,
    gen_task_id          BIGINT       REFERENCES gen_task (id) ON DELETE SET NULL,
    user_id              BIGINT       NOT NULL REFERENCES sys_user (id),
    source_video_key     VARCHAR(512) NOT NULL,
    audio_key            VARCHAR(512),
    subtitle_json_key    VARCHAR(512),
    caption_video_key    VARCHAR(512),
    status               VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    processor_version    VARCHAR(64)  NOT NULL,
    error_message        TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_drama_shot_caption_source UNIQUE (shot_id, source_video_key, processor_version),
    CONSTRAINT ck_drama_shot_caption_status CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'NO_SPEECH', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_drama_shot_caption_shot
    ON drama_shot_caption (shot_id, id DESC);

CREATE TABLE IF NOT EXISTS script_task (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES sys_user (id),
    title            VARCHAR(256) NOT NULL,
    mode             VARCHAR(32)  NOT NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    genre            VARCHAR(128),
    target_episodes  INTEGER      NOT NULL DEFAULT 6,
    model_id         BIGINT REFERENCES ai_model (id),
    input_text       TEXT,
    document         TEXT,
    result_json      JSONB,
    attachments      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    drama_series_id  BIGINT REFERENCES drama_series (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_script_task_mode CHECK (mode IN ('idea', 'adapt', 'review')),
    CONSTRAINT ck_script_task_status CHECK (status IN ('DRAFT', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_script_task_episodes CHECK (target_episodes BETWEEN 1 AND 99)
);

CREATE INDEX IF NOT EXISTS idx_script_task_user_updated ON script_task (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS script_message (
    id               BIGSERIAL PRIMARY KEY,
    task_id          BIGINT       NOT NULL REFERENCES script_task (id) ON DELETE CASCADE,
    user_id          BIGINT       NOT NULL REFERENCES sys_user (id),
    role             VARCHAR(16)  NOT NULL,
    content          TEXT         NOT NULL,
    thinking_process TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_script_message_role CHECK (role IN ('user', 'assistant'))
);

CREATE INDEX IF NOT EXISTS idx_script_message_task_created ON script_message (task_id, created_at ASC);

CREATE TABLE IF NOT EXISTS script_task_version (
    id          BIGSERIAL PRIMARY KEY,
    task_id     BIGINT       NOT NULL REFERENCES script_task (id) ON DELETE CASCADE,
    user_id     BIGINT       NOT NULL REFERENCES sys_user (id),
    version_no  INTEGER      NOT NULL,
    document    TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_script_task_version UNIQUE (task_id, version_no)
);

CREATE INDEX IF NOT EXISTS idx_script_task_version_task ON script_task_version (task_id, version_no DESC);
