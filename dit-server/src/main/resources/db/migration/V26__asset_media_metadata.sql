-- 素材媒体元数据：图片/视频宽高（像素）与音视频时长（毫秒），供选择器返回结构体与卡片展示。
-- 探测失败的存量/新增素材允许为 NULL，上传不因探测失败而中断。
ALTER TABLE asset ADD COLUMN IF NOT EXISTS width INTEGER;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS height INTEGER;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS duration_ms BIGINT;

COMMENT ON COLUMN asset.width IS '媒体宽度（像素），探测失败为 NULL';
COMMENT ON COLUMN asset.height IS '媒体高度（像素），探测失败为 NULL';
COMMENT ON COLUMN asset.duration_ms IS '音视频时长（毫秒），探测失败为 NULL';
