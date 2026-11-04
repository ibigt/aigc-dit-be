-- 高级工作台迁移（自 dev_v1c00 V32 迁入并重编号）：幂等修复脚本，可安全重放。
CREATE TABLE IF NOT EXISTS workflow (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT REFERENCES sys_user(id),
    title         VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    type          VARCHAR(16)  NOT NULL DEFAULT 'image',
    model_id      BIGINT,
    model_label   VARCHAR(128),
    origin        VARCHAR(128),
    demo_key      VARCHAR(64),
    source_path   VARCHAR(256),
    builtin       BOOLEAN      NOT NULL DEFAULT FALSE,
    node_count    INTEGER      NOT NULL DEFAULT 0,
    graph         JSONB        NOT NULL DEFAULT '{"nodes":[],"edges":[]}'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_workflow_type CHECK (type IN ('image', 'video', 'audio', 'import', 'template'))
);

COMMENT ON TABLE workflow IS '高级工作台工作流（画布 JSON；builtin 为官方示例）';
COMMENT ON COLUMN workflow.user_id IS '所属用户；官方示例为空';
COMMENT ON COLUMN workflow.graph IS '画布 nodes/edges；官方示例可只存 demo 键，由前端展开';
COMMENT ON COLUMN workflow.demo_key IS '前端内置示例键，节点为空时用于展开画布';
COMMENT ON COLUMN workflow.source_path IS '需在前端拉取并规范化的 ComfyUI JSON 路径';

CREATE INDEX IF NOT EXISTS idx_workflow_user_updated ON workflow (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS workflow_run (
    id            BIGSERIAL PRIMARY KEY,
    workflow_id   BIGINT REFERENCES workflow(id) ON DELETE SET NULL,
    user_id       BIGINT       NOT NULL REFERENCES sys_user(id),
    model_id      BIGINT,
    status        VARCHAR(16)  NOT NULL,
    progress      INTEGER      NOT NULL DEFAULT 0,
    total         INTEGER      NOT NULL DEFAULT 0,
    error_message VARCHAR(1024),
    steps         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at   TIMESTAMPTZ
);

COMMENT ON TABLE workflow_run IS '工作流运行。生成节点按依赖顺序提交到 gen_task，结果写入我的创作';
COMMENT ON COLUMN workflow_run.steps IS '节点计划与状态：nodeId、taskId、status、dependsOn、outputStorageKey';

CREATE INDEX IF NOT EXISTS idx_workflow_run_user ON workflow_run (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_run_status ON workflow_run (status);

WITH seeds (title, description, type, model_label, origin, demo_key, source_path, node_count, graph) AS (
    VALUES
        ('Minimax-H3 图生视频', '使用本地图像、分辨率选择与 Minimax-H3 原生音画节点生成视频。', 'video', 'Minimax-H3', '光驭画布 · 多参考', 'image-to-video', NULL, 6, '{"nodes":[],"edges":[],"demo":"image-to-video"}'::jsonb),
        ('Wan2.2 文生视频', '从中文创意脚本直接生成视频，节点内完成模型、画幅、清晰度、时长与副本设置。', 'video', 'Wan2.2', '光驭画布 · 文生视频', 'text-to-video', NULL, 3, '{"nodes":[],"edges":[],"demo":"text-to-video"}'::jsonb),
        ('LTX-Video 文生视频', '用人物与场景参考生成近景、广角两个镜头，再清晰汇入剪辑节点。', 'video', 'LTX2.3', '光驭画布 · 镜头变体', 'shot-variations', NULL, 6, '{"nodes":[],"edges":[],"demo":"shot-variations"}'::jsonb),
        ('SDXL 基础出图', '以同一段分镜描述并列生成四张镜头图，并汇总为可复用分镜表。', 'image', 'Minimax-H3', '光驭画布 · 分镜出图', 'image-board', NULL, 6, '{"nodes":[],"edges":[],"demo":"image-board"}'::jsonb),
        ('Minimax-H3 ComfyUI 原生流程', '保留官方输入图与浏览器可用的 ComfyUI 节点工作流，用于高级参数调试。', 'video', 'Minimax-H3', 'ComfyUI 导入', NULL, '/comfyui-workflows/minimax-h3/video_minimax_h3_i2v.json', 9, '{"nodes":[],"edges":[],"source":"/comfyui-workflows/minimax-h3/video_minimax_h3_i2v.json"}'::jsonb)
)
INSERT INTO workflow (user_id, title, description, type, model_label, origin, demo_key, source_path, builtin, node_count, graph)
SELECT NULL, s.title, s.description, s.type, s.model_label, s.origin, s.demo_key, s.source_path, TRUE, s.node_count, s.graph
FROM seeds AS s
WHERE NOT EXISTS (
    SELECT 1
    FROM workflow AS w
    WHERE w.builtin = TRUE
      AND ((s.demo_key IS NOT NULL AND w.demo_key = s.demo_key)
        OR (s.source_path IS NOT NULL AND w.source_path = s.source_path))
);

ALTER TABLE workflow_run
    ADD COLUMN IF NOT EXISTS previous_run_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint AS c
        JOIN pg_attribute AS source_col
            ON source_col.attrelid = c.conrelid AND source_col.attname = 'previous_run_id'
        JOIN pg_attribute AS target_col
            ON target_col.attrelid = c.confrelid AND target_col.attname = 'id'
        WHERE c.contype = 'f'
          AND c.conrelid = 'workflow_run'::regclass
          AND c.confrelid = 'workflow_run'::regclass
          AND c.conkey = ARRAY[source_col.attnum]
          AND c.confkey = ARRAY[target_col.attnum]
          AND c.confdeltype = 'n'
    ) THEN
        ALTER TABLE workflow_run
            ADD CONSTRAINT fk_workflow_run_previous_run_id
            FOREIGN KEY (previous_run_id) REFERENCES workflow_run(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_workflow_run_owner_workflow_id
    ON workflow_run (user_id, workflow_id, id DESC);
