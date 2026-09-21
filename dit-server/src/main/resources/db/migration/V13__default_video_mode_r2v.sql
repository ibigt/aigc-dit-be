-- 产品默认改回参考图；场内第一镜仍由业务层强制 r2v。
ALTER TABLE drama_series
    ALTER COLUMN video_generate_mode SET DEFAULT 'r2v';

UPDATE drama_series
SET video_generate_mode = 'r2v',
    updated_at = NOW()
WHERE video_generate_mode = 'i2v';
