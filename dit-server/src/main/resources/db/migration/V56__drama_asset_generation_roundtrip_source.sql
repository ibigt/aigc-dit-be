-- 外部图 → 图生文 → Seedream 5.0 T2I 的资产图来源
ALTER TABLE drama_asset_generation
    DROP CONSTRAINT IF EXISTS ck_drama_asset_generation_source;

ALTER TABLE drama_asset_generation
    ADD CONSTRAINT ck_drama_asset_generation_source
        CHECK (source IN ('generate', 'upload', 'library', 'roundtrip'));

COMMENT ON COLUMN drama_asset_generation.source IS '来源：generate / upload / library / roundtrip';
