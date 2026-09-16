-- 连续分镜成为新项目默认值；仅迁移已绑定且明确支持 i2v 的视频模型。
ALTER TABLE drama_series
    ALTER COLUMN video_generate_mode SET DEFAULT 'i2v';

WITH continuous_series AS (
    UPDATE drama_series AS s
    SET video_generate_mode = 'i2v',
        video_chain_prev_tail = FALSE,
        updated_at = NOW()
    FROM ai_model AS m
    WHERE s.video_model_id = m.id
      AND LOWER(COALESCE(NULLIF(s.video_adapter_key, ''), m.adapter_key, '')) IN (
          'seedance-2.0',
          'seedance-2.5',
          'minimax-h3',
          'agnes-video-2.5'
      )
    RETURNING s.id
)
UPDATE drama_shot AS shot
SET video_generate_mode = NULL,
    video_chain_prev_tail = NULL,
    updated_at = NOW()
WHERE shot.series_id IN (SELECT id FROM continuous_series)
  AND (shot.video_generate_mode IS NULL OR shot.video_generate_mode = 'r2v')
  AND COALESCE(shot.video_chain_prev_tail, FALSE) = FALSE;
