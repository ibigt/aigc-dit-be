CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_recharge_request
    ON credit_ledger (user_id, ref_id)
    WHERE reason = 'RECHARGE' AND ref_id IS NOT NULL;

COMMENT ON INDEX uk_credit_recharge_request IS '防止同一用户的模拟充值请求被重复入账';
