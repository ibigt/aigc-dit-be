ALTER TABLE drama_shot ADD COLUMN IF NOT EXISTS shot_beats JSONB;

COMMENT ON COLUMN drama_shot.shot_beats IS '分镜内部镜头序列：起始站位 blocking，以及 1–3 个镜头 beats（景别、动作、连续性、本镜头台词）；空则仍按单句编译';
