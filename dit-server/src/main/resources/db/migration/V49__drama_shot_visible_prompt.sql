-- 分镜用户可见核心正文。叙述里的资产写成 @名字，台词保持普通名字。
-- 本地库的 Flyway 版本已经到 48，V47 不会再执行，所以改挂在 49。
ALTER TABLE drama_shot
    ADD COLUMN IF NOT EXISTS visible_prompt TEXT;

COMMENT ON COLUMN drama_shot.visible_prompt IS '用户可见、可编辑的核心正文。镜头说明里的资产是 @名字，台词是普通名字';
