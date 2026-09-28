-- AI 剧本创作任务、对话与文档版本
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
    drama_series_id  BIGINT REFERENCES drama_series (id),
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
