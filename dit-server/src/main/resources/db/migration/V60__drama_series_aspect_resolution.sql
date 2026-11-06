-- 分镜画幅/清晰度已扩展到 21:9、4:3、3:4 与 1080P、2K，
-- 旧检查约束仍只允许 16:9 / 9:16 / 1:1 和 480P / 720P，更新时会被拒绝。
ALTER TABLE drama_series DROP CONSTRAINT IF EXISTS ck_drama_series_aspect;
ALTER TABLE drama_series
    ADD CONSTRAINT ck_drama_series_aspect
    CHECK (aspect_ratio IN ('21:9', '16:9', '4:3', '1:1', '3:4', '9:16'));

ALTER TABLE drama_series DROP CONSTRAINT IF EXISTS ck_drama_series_resolution;
ALTER TABLE drama_series
    ADD CONSTRAINT ck_drama_series_resolution
    CHECK (default_resolution IN ('480P', '720P', '1080P', '2K'));

COMMENT ON COLUMN drama_series.aspect_ratio IS '画幅（21:9 / 16:9 / 4:3 / 1:1 / 3:4 / 9:16）';
COMMENT ON COLUMN drama_series.default_resolution IS '默认清晰度（480P / 720P / 1080P / 2K）';
