-- Reconcile databases already at V59 that skipped the workflow migrations.
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

-- 高级工作台模板库元数据（需求 4.3.1.2）：模板编码、分类、标签、封面、版本、模型适配器、节点拓扑、默认参数
-- 拓扑节点坐标使用缩略图坐标系（440x230），前端据此绘制 SVG 缩略图，避免加载完整画布。

ALTER TABLE workflow ADD COLUMN IF NOT EXISTS template_code   VARCHAR(64);
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS category         VARCHAR(32);
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS tags             JSONB        NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS cover_path       VARCHAR(256);
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS version          VARCHAR(16) NOT NULL DEFAULT '1.0';
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS model_adapter    VARCHAR(64);
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS topology         JSONB;
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS default_params   JSONB        NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN workflow.template_code IS '模板编码：官方模板唯一标识，自定义模板为空';
COMMENT ON COLUMN workflow.category IS '模板分类：video / image / audio';
COMMENT ON COLUMN workflow.tags IS '模板标签数组，用于标签筛选';
COMMENT ON COLUMN workflow.cover_path IS '模板封面图路径';
COMMENT ON COLUMN workflow.version IS '模板版本号';
COMMENT ON COLUMN workflow.model_adapter IS '模型适配器：minimax-h3 / wan2.2 / ltx2.3 / sdxl / comfyui';
COMMENT ON COLUMN workflow.topology IS '节点拓扑缩略图数据：nodes（x,y,w,label,tone）与 edges（节点下标对）';
COMMENT ON COLUMN workflow.default_params IS '实例化时套用的默认参数：ratio / resolution / duration / copies 等';

