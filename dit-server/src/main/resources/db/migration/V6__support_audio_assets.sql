-- 旧环境的 V1 只允许 image/video，会导致音频素材写入时违反检查约束。
ALTER TABLE asset DROP CONSTRAINT IF EXISTS ck_asset_kind;

ALTER TABLE asset
    ADD CONSTRAINT ck_asset_kind
    CHECK (kind IS NULL OR kind IN ('image', 'video', 'audio'));

COMMENT ON COLUMN asset.kind
    IS '素材种类（image 图像、video 视频、audio 音频）；由 mime_type 推导或上传时写入';
