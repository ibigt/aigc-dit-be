-- 高级工作台：下线内置官方示例 / 官方模板（需求变更 14.15）
-- 背景：V53 插入 5 条 builtin 官方示例，V56 把它们补成官方模板并新增第 6 条，画布均为占位空壳。
-- 现按产品决定整体下线；模板库保留，后续由「把生成的工作流保存为模板」写入用户自己的记录。
-- 历史迁移已执行，不能改动 V53/V56 的文件内容（Flyway checksum），因此统一在本迁移删除。
-- workflow_run / workflow_run_archive 的 workflow_id 外键为 ON DELETE SET NULL，历史运行记录保留，仅解除关联。

DELETE FROM workflow
WHERE builtin = TRUE
   OR template_code IS NOT NULL;
