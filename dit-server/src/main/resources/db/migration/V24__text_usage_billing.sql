CREATE TABLE IF NOT EXISTS text_usage_billing (
    id                       BIGSERIAL PRIMARY KEY,
    billing_ref              VARCHAR(64)  NOT NULL,
    user_id                  BIGINT       NOT NULL REFERENCES sys_user (id),
    series_id                BIGINT       NOT NULL,
    model_id                 BIGINT       NOT NULL,
    operation                VARCHAR(64)  NOT NULL,
    prompt_tokens            BIGINT       NOT NULL DEFAULT 0,
    completion_tokens        BIGINT       NOT NULL DEFAULT 0,
    cached_prompt_tokens     BIGINT       NOT NULL DEFAULT 0,
    credit_cost              BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_text_usage_billing_ref UNIQUE (billing_ref)
);

CREATE INDEX IF NOT EXISTS idx_text_usage_billing_user
    ON text_usage_billing (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_text_usage_billing_created
    ON text_usage_billing (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_text_usage_billing_model_operation
    ON text_usage_billing (model_id, operation, created_at DESC);

COMMENT ON TABLE text_usage_billing IS '文本模型业务任务用量与积分结算记录';
COMMENT ON COLUMN text_usage_billing.billing_ref IS '任务级幂等参考号';
COMMENT ON COLUMN text_usage_billing.operation IS '文本业务类型：SCRIPT_PARSE / ASSET_ENRICH / SHOT_DRAFT';
COMMENT ON COLUMN text_usage_billing.cached_prompt_tokens IS '输入 Token 中命中缓存的数量';
COMMENT ON COLUMN text_usage_billing.credit_cost IS '本业务任务最终扣除的积分';
