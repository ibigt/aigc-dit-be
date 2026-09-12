-- 分镜视频提示词手动覆盖
ALTER TABLE drama_shot ADD COLUMN IF NOT EXISTS prompt_override VARCHAR(4000);
COMMENT ON COLUMN drama_shot.prompt_override IS '用户手动覆盖的视频提示词；非空时优先于 compiled_prompt 出片';
