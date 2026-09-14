-- 分镜 / 系列默认时长从仅 5/10/15 放开为 5–15 逐秒
ALTER TABLE drama_series DROP CONSTRAINT IF EXISTS ck_drama_series_duration;
ALTER TABLE drama_series
    ADD CONSTRAINT ck_drama_series_duration
    CHECK (default_duration_sec >= 5 AND default_duration_sec <= 15);

ALTER TABLE drama_shot DROP CONSTRAINT IF EXISTS ck_drama_shot_duration;
ALTER TABLE drama_shot
    ADD CONSTRAINT ck_drama_shot_duration
    CHECK (duration_sec >= 5 AND duration_sec <= 15);

COMMENT ON COLUMN drama_series.default_duration_sec IS '默认单镜时长秒（5–15）';
COMMENT ON COLUMN drama_shot.duration_sec IS '时长秒（5–15）';
