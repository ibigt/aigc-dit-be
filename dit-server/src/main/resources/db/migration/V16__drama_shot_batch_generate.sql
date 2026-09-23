-- 分镜批量生成：批次任务与条目（编排层，不改单镜出片核心）

CREATE TABLE IF NOT EXISTS drama_shot_batch_job (
    id                      BIGSERIAL PRIMARY KEY,
    episode_id              BIGINT       NOT NULL REFERENCES drama_episode (id) ON DELETE CASCADE,
    series_id               BIGINT       NOT NULL REFERENCES drama_series (id) ON DELETE CASCADE,
    user_id                 BIGINT       NOT NULL REFERENCES sys_user (id),
    status                  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    video_generate_mode     VARCHAR(16)  NOT NULL,
    chain_prev_tail         BOOLEAN      NOT NULL DEFAULT FALSE,
    i2v_first_frame_mode    VARCHAR(16),
    first_shot_auto_still   BOOLEAN      NOT NULL DEFAULT TRUE,
    concurrent              BOOLEAN      NOT NULL DEFAULT FALSE,
    error_message           TEXT,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_drama_shot_batch_job_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_drama_shot_batch_job_mode CHECK (
        video_generate_mode IN ('r2v', 'i2v')
    ),
    CONSTRAINT ck_drama_shot_batch_job_i2v_frame CHECK (
        i2v_first_frame_mode IS NULL
            OR i2v_first_frame_mode IN ('auto', 'prev_tail')
    )
);

CREATE INDEX IF NOT EXISTS idx_drama_shot_batch_job_episode_status
    ON drama_shot_batch_job (episode_id, status);

COMMENT ON TABLE drama_shot_batch_job IS '漫剧分镜批量出片任务';
COMMENT ON COLUMN drama_shot_batch_job.status IS 'PENDING|RUNNING|SUCCEEDED|FAILED|CANCELLED';
COMMENT ON COLUMN drama_shot_batch_job.video_generate_mode IS 'r2v 参考图 / i2v 首尾帧';
COMMENT ON COLUMN drama_shot_batch_job.chain_prev_tail IS 'r2v 时是否衔接上一镜落幅';
COMMENT ON COLUMN drama_shot_batch_job.i2v_first_frame_mode IS 'i2v 首帧：auto 自动静帧 / prev_tail 引用上一镜落幅';
COMMENT ON COLUMN drama_shot_batch_job.first_shot_auto_still IS 'i2v+prev_tail 时场首镜是否自动静帧';
COMMENT ON COLUMN drama_shot_batch_job.concurrent IS 'true=并发提交；false=按 shot_index 串行';

CREATE TABLE IF NOT EXISTS drama_shot_batch_item (
    id                      BIGSERIAL PRIMARY KEY,
    job_id                  BIGINT       NOT NULL REFERENCES drama_shot_batch_job (id) ON DELETE CASCADE,
    shot_id                 BIGINT       NOT NULL REFERENCES drama_shot (id) ON DELETE CASCADE,
    shot_index              INTEGER      NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    gen_task_id             BIGINT       REFERENCES gen_task (id) ON DELETE SET NULL,
    error_message           TEXT,
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ,
    CONSTRAINT ck_drama_shot_batch_item_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    )
);

CREATE INDEX IF NOT EXISTS idx_drama_shot_batch_item_job_index
    ON drama_shot_batch_item (job_id, shot_index);

CREATE INDEX IF NOT EXISTS idx_drama_shot_batch_item_shot_status
    ON drama_shot_batch_item (shot_id, status);

COMMENT ON TABLE drama_shot_batch_item IS '漫剧分镜批量出片条目';
COMMENT ON COLUMN drama_shot_batch_item.status IS 'PENDING|RUNNING|SUCCEEDED|FAILED|CANCELLED';
COMMENT ON COLUMN drama_shot_batch_item.gen_task_id IS '当前关联的 gen_task（静帧续出片时可能更换）';
