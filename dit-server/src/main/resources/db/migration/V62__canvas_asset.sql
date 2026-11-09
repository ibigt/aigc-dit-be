-- 画布资产（V1C20 1.3）：节点配置快照，跨画布 / 跨会话复用。
-- 与 workflow.graph 同样用 JSONB 存 {nodes, edges}，但不存视口、运行态与产物。

CREATE TABLE IF NOT EXISTS canvas_asset (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES sys_user(id),
    name        VARCHAR(128) NOT NULL,
    node_kind   VARCHAR(64),
    node_count  INTEGER NOT NULL DEFAULT 1,
    graph       JSONB NOT NULL DEFAULT '{"nodes":[],"edges":[]}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_canvas_asset_node_count CHECK (node_count BETWEEN 1 AND 20)
);

COMMENT ON TABLE canvas_asset IS '画布资产：右键节点「保存为资产」生成的节点配置快照';
COMMENT ON COLUMN canvas_asset.graph IS '节点子集 {nodes, edges}；不含 runtime/outputs/viewport';

CREATE INDEX IF NOT EXISTS idx_canvas_asset_user_created ON canvas_asset (user_id, created_at DESC);
