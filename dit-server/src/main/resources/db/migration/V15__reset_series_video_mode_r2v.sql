-- 去除顶栏系列级生成方式后：将系列统一回正为参考图。
-- 不改动 drama_shot 上已显式为 i2v 的本镜覆盖（侧栏历史选择保留）。
-- V13 已做过类似系列回正；本 migration 覆盖之后又被顶栏改回 i2v / chain 的数据。

UPDATE drama_series
SET video_generate_mode = 'r2v',
    video_chain_prev_tail = FALSE,
    updated_at = NOW()
WHERE video_generate_mode = 'i2v'
   OR video_chain_prev_tail = TRUE;
