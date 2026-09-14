-- 角色音色参考：仅用于支持音频引用的视频模型（首期 MiniMax-H3）。
ALTER TABLE drama_asset ADD COLUMN IF NOT EXISTS voice_reference_key VARCHAR(512);
ALTER TABLE drama_asset ADD COLUMN IF NOT EXISTS voice_reference_mime VARCHAR(128);
COMMENT ON COLUMN drama_asset.voice_reference_key IS '角色音色参考音频的对象存储 key';
COMMENT ON COLUMN drama_asset.voice_reference_mime IS '角色音色参考音频 MIME';
