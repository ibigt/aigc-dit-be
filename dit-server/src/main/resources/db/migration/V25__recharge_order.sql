CREATE TABLE IF NOT EXISTS recharge_order (
    id                 BIGSERIAL PRIMARY KEY,
    order_no           VARCHAR(64)  NOT NULL UNIQUE,
    request_id         VARCHAR(64)  NOT NULL,
    user_id            BIGINT       NOT NULL REFERENCES sys_user (id),
    amount_yuan        INTEGER      NOT NULL,
    credit_amount      BIGINT       NOT NULL,
    payment_channel    VARCHAR(32)  NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    provider_trade_no  VARCHAR(128),
    paid_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_recharge_order_user_request UNIQUE (user_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_recharge_order_created ON recharge_order (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_recharge_order_user ON recharge_order (user_id, created_at DESC);

COMMENT ON TABLE recharge_order IS '充值订单及支付对账依据';
COMMENT ON COLUMN recharge_order.request_id IS '前端幂等请求号';
COMMENT ON COLUMN recharge_order.provider_trade_no IS '第三方支付流水号；Mock 环境生成模拟值';
