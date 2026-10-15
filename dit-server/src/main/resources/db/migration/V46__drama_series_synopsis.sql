-- 短剧项目简介：创建时填写，最长 200 字。
-- 版本用 V46：目标库的 flyway_schema_history 已到 V45，且 V42 已被「script prompt」占用。
ALTER TABLE drama_series
    ADD COLUMN IF NOT EXISTS synopsis VARCHAR(200);

COMMENT ON COLUMN drama_series.synopsis IS '故事简介，创建时填写，最长 200 字';
