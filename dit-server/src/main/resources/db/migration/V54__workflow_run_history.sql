-- An unsaved editor explicitly links its owned run history; saved workflow lookups remain owner scoped.
ALTER TABLE workflow_run
    ADD COLUMN previous_run_id BIGINT REFERENCES workflow_run(id) ON DELETE SET NULL;

CREATE INDEX idx_workflow_run_owner_workflow_id
    ON workflow_run (user_id, workflow_id, id DESC);
