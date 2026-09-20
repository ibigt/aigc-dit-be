-- 成片入片分镜清单（有序 shot id），用于刷新后恢复勾选与按入片镜头判 stale
ALTER TABLE drama_episode
    ADD COLUMN IF NOT EXISTS film_shot_ids JSONB;

COMMENT ON COLUMN drama_episode.film_shot_ids
    IS '最近一次成片入片的 shot id 有序列表（JSON 数组）；空表示历史成片未记录';
