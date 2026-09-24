-- 剧本任务关联短剧工程；删除短剧工程时解除关联，避免外键阻止删除。
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT con.conname
      INTO constraint_name
      FROM pg_constraint con
     WHERE con.conrelid = 'script_task'::regclass
       AND con.confrelid = 'drama_series'::regclass
       AND con.contype = 'f'
     LIMIT 1;

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE script_task DROP CONSTRAINT %I', constraint_name);
    END IF;

    ALTER TABLE script_task
        ADD CONSTRAINT fk_script_task_drama_series
        FOREIGN KEY (drama_series_id) REFERENCES drama_series (id) ON DELETE SET NULL;
END $$;
