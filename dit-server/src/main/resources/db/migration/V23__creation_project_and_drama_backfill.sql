ALTER TABLE creation ADD COLUMN IF NOT EXISTS project_kind VARCHAR(16);
ALTER TABLE creation ADD COLUMN IF NOT EXISTS project_id BIGINT;

COMMENT ON COLUMN creation.project_kind IS '所属项目类型：quick 快速创作，drama 短剧';
COMMENT ON COLUMN creation.project_id IS '所属项目 ID：快速创作为会话 ID，短剧为剧集项目 ID';

UPDATE creation
SET project_kind = 'quick',
    project_id = session_id
WHERE session_id IS NOT NULL
  AND project_kind IS NULL;

UPDATE creation AS item
SET project_kind = 'drama',
    project_id = (task.params->>'seriesId')::bigint
FROM gen_task AS task
WHERE item.task_id = task.id
  AND item.project_kind IS NULL
  AND COALESCE(task.params->>'source', '') = '漫剧'
  AND task.params->>'seriesId' ~ '^[0-9]+$';

INSERT INTO creation (
    session_id, user_id, task_id, model_id, title, category, storage_key, poster_key,
    prompt, ratio, resolution, duration_sec, credit_cost, copy_index, created_at,
    project_kind, project_id
)
SELECT
    NULL,
    task.user_id,
    task.id,
    task.model_id,
    LEFT(COALESCE(NULLIF(BTRIM(task.prompt), ''), '未命名作品'), 24),
    task.category,
    output.value,
    CASE WHEN output.ordinality = 1 THEN task.poster_key ELSE NULL END,
    task.prompt,
    NULLIF(task.params->>'ratio', ''),
    NULLIF(task.params->>'resolution', ''),
    CASE
        WHEN task.params->>'durationSec' ~ '^[0-9]+$' THEN (task.params->>'durationSec')::int
        ELSE NULL
    END,
    GREATEST(0, COALESCE(task.credit_cost, 0) / GREATEST(
            1,
            CASE
                WHEN task.output_keys IS NOT NULL
                    AND jsonb_typeof(task.output_keys) = 'array'
                    AND jsonb_array_length(task.output_keys) > 0
                    THEN LEAST(jsonb_array_length(task.output_keys), 4)
                ELSE 1
            END
    )),
    output.ordinality::int,
    COALESCE(task.finished_at, task.created_at),
    'drama',
    CASE
        WHEN task.params->>'seriesId' ~ '^[0-9]+$' THEN (task.params->>'seriesId')::bigint
        ELSE NULL
    END
FROM gen_task AS task
CROSS JOIN LATERAL (
    SELECT value, ordinality
    FROM jsonb_array_elements_text(
        CASE
            WHEN task.output_keys IS NOT NULL
                AND jsonb_typeof(task.output_keys) = 'array'
                AND jsonb_array_length(task.output_keys) > 0
                THEN task.output_keys
            WHEN task.output_storage_key IS NOT NULL AND task.output_storage_key <> ''
                THEN jsonb_build_array(task.output_storage_key)
            ELSE '[]'::jsonb
        END
    ) WITH ORDINALITY AS keys(value, ordinality)
    WHERE ordinality <= 4
) AS output
WHERE task.status = 'SUCCEEDED'
  AND lower(task.category) IN ('image', 'video')
  AND COALESCE(task.params->>'source', '') = '漫剧'
  AND NOT EXISTS (SELECT 1 FROM creation AS existing WHERE existing.task_id = task.id);
