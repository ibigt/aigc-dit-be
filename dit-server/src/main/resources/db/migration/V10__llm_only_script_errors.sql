-- 文本模型解析/拆镜失败后仍保持待处理，把原因钉在剧集上供空页展示。
ALTER TABLE drama_episode
    ADD COLUMN IF NOT EXISTS script_analyze_error TEXT;

ALTER TABLE drama_episode
    ADD COLUMN IF NOT EXISTS storyboard_draft_error TEXT;

COMMENT ON COLUMN drama_episode.script_analyze_error
    IS '最近一次剧本分析失败原因；成功或重新开始分析时清空';
COMMENT ON COLUMN drama_episode.storyboard_draft_error
    IS '最近一次分镜拆分失败原因；成功或重新开始拆镜时清空';
COMMENT ON COLUMN drama_series.text_model_id
    IS '绑定的文本模型（ai_model.id，category=text）；空表示未绑定，剧本解析/分镜拆分不可用';
COMMENT ON COLUMN drama_episode.engine
    IS '分场引擎：pending 尚未用文本模型成功解析；rules 仅兼容旧数据；llm 文本模型';
