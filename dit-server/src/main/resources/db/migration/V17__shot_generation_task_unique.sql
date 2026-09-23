-- 同一分镜 + 同一 gen_task 只保留一条生成记录（清理并发落库产生的重复）

-- 若选用指向将被删除的重复行，先改挂到同 task 的最早记录
UPDATE drama_shot s
SET selected_generation_id = keep.id
FROM drama_shot_generation dup
JOIN drama_shot_generation keep
  ON keep.shot_id = dup.shot_id
 AND keep.gen_task_id = dup.gen_task_id
 AND keep.gen_task_id IS NOT NULL
 AND keep.id = (
     SELECT MIN(g.id)
     FROM drama_shot_generation g
     WHERE g.shot_id = dup.shot_id
       AND g.gen_task_id = dup.gen_task_id
 )
WHERE s.selected_generation_id = dup.id
  AND dup.id <> keep.id;

DELETE FROM drama_shot_generation a
    USING drama_shot_generation b
WHERE a.shot_id = b.shot_id
  AND a.gen_task_id IS NOT NULL
  AND a.gen_task_id = b.gen_task_id
  AND a.id > b.id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_drama_shot_generation_shot_task
    ON drama_shot_generation (shot_id, gen_task_id)
    WHERE gen_task_id IS NOT NULL;

COMMENT ON INDEX uk_drama_shot_generation_shot_task IS '同一任务结果只落一条分镜生成记录，防止轮询并发重复插入';
