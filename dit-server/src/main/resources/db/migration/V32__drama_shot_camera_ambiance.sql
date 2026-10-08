ALTER TABLE drama_shot ADD COLUMN IF NOT EXISTS camera_motion TEXT;
ALTER TABLE drama_shot ADD COLUMN IF NOT EXISTS ambiance_audio TEXT;

COMMENT ON COLUMN drama_shot.camera_motion IS '景别与运镜，编译进视频提示词的 Camera 行';
COMMENT ON COLUMN drama_shot.ambiance_audio IS '画内环境声，编译进视频提示词的 Ambiance 行';
