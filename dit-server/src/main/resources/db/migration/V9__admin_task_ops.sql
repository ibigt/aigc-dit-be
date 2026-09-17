-- 管理端请求日志 / 任务队列：记录首次进入 RUNNING 的时间，并补查询索引。
ALTER TABLE gen_task
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_gen_task_created ON gen_task (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gen_task_status_created ON gen_task (status, created_at DESC);

COMMENT ON TABLE gen_task IS '异步生成任务表；仪表盘、请求日志、任务队列均读本表，不另做 HTTP 访问日志';
COMMENT ON COLUMN gen_task.started_at IS '首次进入 RUNNING 的时间（排队耗时 = started_at - created_at；未开始则空）';
COMMENT ON COLUMN gen_task.created_at IS '创建时间';
COMMENT ON COLUMN gen_task.finished_at IS '结束时间（成功、失败或取消）';
