-- 资产生成记录支持从素材库引用（source = library）

ALTER TABLE drama_asset_generation
    DROP CONSTRAINT IF EXISTS ck_drama_asset_generation_source;

ALTER TABLE drama_asset_generation
    ADD CONSTRAINT ck_drama_asset_generation_source
        CHECK (source IN ('generate', 'upload', 'library'));

COMMENT ON COLUMN drama_asset_generation.source IS '来源：generate / upload / library';
