-- V18 可能已记入 flyway_schema_history 但未真正放开 library；此处幂等修复

ALTER TABLE drama_asset_generation
    DROP CONSTRAINT IF EXISTS ck_drama_asset_generation_source;

ALTER TABLE drama_asset_generation
    ADD CONSTRAINT ck_drama_asset_generation_source
        CHECK (source IN ('generate', 'upload', 'library'));

COMMENT ON COLUMN drama_asset_generation.source IS '来源：generate / upload / library';
