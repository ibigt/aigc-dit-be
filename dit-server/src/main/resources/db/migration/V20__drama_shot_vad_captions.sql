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
