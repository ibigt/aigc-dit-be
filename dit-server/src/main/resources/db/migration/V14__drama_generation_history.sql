-- 资产 / 分镜生成历史（抽卡）：多版本保留，父表 selected_generation_id 指向当前选用

CREATE TABLE IF NOT EXISTS drama_asset_generation (
    id                  BIGSERIAL PRIMARY KEY,
    asset_id            BIGINT       NOT NULL REFERENCES drama_asset (id) ON DELETE CASCADE,
    user_id             BIGINT       NOT NULL REFERENCES sys_user (id),
    storage_key         VARCHAR(512) NOT NULL,
    gen_task_id         BIGINT       REFERENCES gen_task (id) ON DELETE SET NULL,
    source              VARCHAR(16)  NOT NULL,
    label               VARCHAR(64)  NOT NULL,
    resolution          VARCHAR(16),
    library_asset_id    BIGINT       REFERENCES asset (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_drama_asset_generation_source CHECK (source IN ('generate', 'upload'))
);

CREATE INDEX IF NOT EXISTS idx_drama_asset_generation_asset
    ON drama_asset_generation (asset_id, id DESC);

COMMENT ON TABLE drama_asset_generation IS '漫剧资产生成/上传历史；父表 selected_generation_id 指向当前选用';
COMMENT ON COLUMN drama_asset_generation.storage_key IS '图片对象存储 key';
COMMENT ON COLUMN drama_asset_generation.source IS '来源：generate / upload';
COMMENT ON COLUMN drama_asset_generation.label IS '展示标签，如 生成方案 3';
COMMENT ON COLUMN drama_asset_generation.library_asset_id IS '导入素材库后回写；用于已入库态';

CREATE TABLE IF NOT EXISTS drama_shot_generation (
    id                  BIGSERIAL PRIMARY KEY,
    shot_id             BIGINT       NOT NULL REFERENCES drama_shot (id) ON DELETE CASCADE,
    user_id             BIGINT       NOT NULL REFERENCES sys_user (id),
    video_key           VARCHAR(512) NOT NULL,
    video_poster_key    VARCHAR(512),
    video_tail_key      VARCHAR(512),
    gen_task_id         BIGINT       REFERENCES gen_task (id) ON DELETE SET NULL,
    source              VARCHAR(16)  NOT NULL,
    label               VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_drama_shot_generation_source CHECK (source IN ('generate', 'upload'))
);

CREATE INDEX IF NOT EXISTS idx_drama_shot_generation_shot
    ON drama_shot_generation (shot_id, id DESC);

COMMENT ON TABLE drama_shot_generation IS '漫剧分镜出片/上传历史；父表 selected_generation_id 指向当前选用';
COMMENT ON COLUMN drama_shot_generation.video_key IS '成片对象存储 key';
COMMENT ON COLUMN drama_shot_generation.source IS '来源：generate / upload';
COMMENT ON COLUMN drama_shot_generation.label IS '展示标签，如 第 N 次生成';

ALTER TABLE drama_asset
    ADD COLUMN IF NOT EXISTS selected_generation_id BIGINT;

ALTER TABLE drama_shot
    ADD COLUMN IF NOT EXISTS selected_generation_id BIGINT;

COMMENT ON COLUMN drama_asset.selected_generation_id IS '当前选用的资产生成记录（关联 drama_asset_generation.id）';
COMMENT ON COLUMN drama_shot.selected_generation_id IS '当前选用的分镜生成记录（关联 drama_shot_generation.id）';

-- 存量回填：已有参考图 / 成片各插一条 generation 并设为选用
INSERT INTO drama_asset_generation (asset_id, user_id, storage_key, gen_task_id, source, label, library_asset_id, created_at)
SELECT a.id,
       a.user_id,
       a.image_storage_key,
       a.gen_task_id,
       CASE WHEN a.gen_task_id IS NULL THEN 'upload' ELSE 'generate' END,
       CASE WHEN a.gen_task_id IS NULL THEN '上传 1' ELSE '生成方案 1' END,
       a.image_asset_id,
       COALESCE(a.updated_at, a.created_at, NOW())
FROM drama_asset a
WHERE a.image_storage_key IS NOT NULL
  AND TRIM(a.image_storage_key) <> ''
  AND a.selected_generation_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM drama_asset_generation g WHERE g.asset_id = a.id
  );

UPDATE drama_asset a
SET selected_generation_id = g.id
FROM drama_asset_generation g
WHERE g.asset_id = a.id
  AND a.selected_generation_id IS NULL
  AND a.image_storage_key IS NOT NULL
  AND TRIM(a.image_storage_key) <> '';

INSERT INTO drama_shot_generation (shot_id, user_id, video_key, video_poster_key, video_tail_key, gen_task_id, source, label, created_at)
SELECT s.id,
       s.user_id,
       s.video_key,
       s.video_poster_key,
       s.video_tail_key,
       s.gen_task_id,
       CASE WHEN s.gen_task_id IS NULL THEN 'upload' ELSE 'generate' END,
       '第 1 次生成',
       COALESCE(s.updated_at, s.created_at, NOW())
FROM drama_shot s
WHERE s.video_key IS NOT NULL
  AND TRIM(s.video_key) <> ''
  AND s.selected_generation_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM drama_shot_generation g WHERE g.shot_id = s.id
  );

UPDATE drama_shot s
SET selected_generation_id = g.id
FROM drama_shot_generation g
WHERE g.shot_id = s.id
  AND s.selected_generation_id IS NULL
  AND s.video_key IS NOT NULL
  AND TRIM(s.video_key) <> '';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_drama_asset_selected_generation'
    ) THEN
        ALTER TABLE drama_asset
            ADD CONSTRAINT fk_drama_asset_selected_generation
                FOREIGN KEY (selected_generation_id)
                REFERENCES drama_asset_generation (id)
                ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_drama_shot_selected_generation'
    ) THEN
        ALTER TABLE drama_shot
            ADD CONSTRAINT fk_drama_shot_selected_generation
                FOREIGN KEY (selected_generation_id)
                REFERENCES drama_shot_generation (id)
                ON DELETE SET NULL;
    END IF;
END $$;
