ALTER TABLE recharge_order ADD COLUMN IF NOT EXISTS qr_code VARCHAR(512);

COMMENT ON COLUMN recharge_order.qr_code IS '支付二维码内容（code_url）；Mock 渠道为模拟值';
