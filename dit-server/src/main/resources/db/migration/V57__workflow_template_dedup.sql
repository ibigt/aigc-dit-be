-- 高级工作台：清理历史迁移重复插入的官方模板
-- 背景：共享库先后执行过 dev 分支 V26/V29 的种子插入与迁移而来的 V53（种子内容完全相同），
-- 5 个官方模板各有 3 份拷贝；V56 按标题 UPDATE 又给每一份都补齐了模板元数据，
-- 导致 /api/workflows/templates 返回 16 条而不是 6 条。
-- 处理：每个官方模板（按 COALESCE(demo_key, source_path) 分组）只保留一行；
-- 优先保留被 workflow_run 引用的行（避免运行记录的 workflow_id 被 SET NULL），否则保留 id 最小的一行。

DELETE FROM workflow w
WHERE w.builtin = TRUE
  AND COALESCE(w.demo_key, w.source_path) IS NOT NULL
  AND w.id <> (
      SELECT keep.id
      FROM workflow keep
      WHERE keep.builtin = TRUE
        AND COALESCE(keep.demo_key, keep.source_path) = COALESCE(w.demo_key, w.source_path)
      ORDER BY EXISTS (SELECT 1 FROM workflow_run r WHERE r.workflow_id = keep.id) DESC,
               keep.id
      LIMIT 1
  );
