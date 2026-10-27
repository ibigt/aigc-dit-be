-- 角色资产多版本：version_label / parent_asset_id；旧「名·后缀」拆成同名 + 标签。

ALTER TABLE drama_asset
    ADD COLUMN IF NOT EXISTS version_label VARCHAR(32);

ALTER TABLE drama_asset
    ADD COLUMN IF NOT EXISTS parent_asset_id BIGINT;

COMMENT ON COLUMN drama_asset.version_label IS '版本展示名（湿身/校服）；基础版可空或「基础」';
COMMENT ON COLUMN drama_asset.parent_asset_id IS '延伸父版本 asset.id；基础版为空';
COMMENT ON COLUMN drama_asset.name IS '角色族基础名（如洛白）；版本用 state_tag + version_label 区分';
COMMENT ON COLUMN drama_asset.state_tag IS '版本 slug：base 或用户短标识（wet / school-uniform 等）';

-- 名含间隔号：拆成基础名 + 版本标签；state_tag 按后缀映射。
UPDATE drama_asset
SET version_label = TRIM(BOTH FROM SUBSTRING(name FROM '[·・](.+)$')),
    name = TRIM(BOTH FROM SUBSTRING(name FROM '^(.+?)[·・]'))
WHERE type = '角色'
  AND (name LIKE '%·%' OR name LIKE '%・%')
  AND (version_label IS NULL OR BTRIM(version_label) = '');

UPDATE drama_asset
SET state_tag = CASE
        WHEN version_label ILIKE '%入梦%' OR version_label ILIKE '%梦境%' OR version_label ILIKE '%dream%' THEN 'dream'
        WHEN version_label ILIKE '%换装%' OR version_label ILIKE '%costume%' THEN 'costume'
        WHEN version_label ILIKE '%湿身%' OR version_label ILIKE '%wet%' THEN 'wet'
        WHEN version_label IS NOT NULL AND BTRIM(version_label) <> '' AND state_tag IN ('base', 'custom') THEN
            'v' || SUBSTRING(MD5(version_label) FROM 1 FOR 8)
        ELSE state_tag
    END
WHERE type = '角色'
  AND version_label IS NOT NULL
  AND BTRIM(version_label) <> '';

-- 注意：不给无间隔号的旧 dream/costume 行强行补 version_label，
-- 避免「梦魇」这类独立角色被写成「梦魇·入梦」。

-- 父版本指向同集同类型同名的 base（若存在）。
UPDATE drama_asset child
SET parent_asset_id = base.id
FROM drama_asset base
WHERE child.type = '角色'
  AND child.state_tag IS DISTINCT FROM 'base'
  AND child.parent_asset_id IS NULL
  AND base.episode_id = child.episode_id
  AND base.type = child.type
  AND base.name = child.name
  AND base.state_tag = 'base'
  AND base.id <> child.id;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_drama_asset_parent'
    ) THEN
        ALTER TABLE drama_asset
            ADD CONSTRAINT fk_drama_asset_parent
                FOREIGN KEY (parent_asset_id) REFERENCES drama_asset (id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_drama_asset_parent ON drama_asset (parent_asset_id);
CREATE INDEX IF NOT EXISTS idx_drama_asset_family ON drama_asset (episode_id, type, name);
