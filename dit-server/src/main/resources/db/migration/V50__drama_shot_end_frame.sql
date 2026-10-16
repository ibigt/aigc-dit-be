-- 用户设置的尾帧，和出片落幅 video_tail_key 不是一回事。
ALTER TABLE drama_shot
    ADD COLUMN IF NOT EXISTS end_frame_key VARCHAR(512),
    ADD COLUMN IF NOT EXISTS end_frame_origin VARCHAR(16);

COMMENT ON COLUMN drama_shot.end_frame_key IS '用户设置的尾帧对象 key：本地上传或引用本集资产图';
COMMENT ON COLUMN drama_shot.end_frame_origin IS '尾帧来源：upload 本地上传 / asset 本集资产';
