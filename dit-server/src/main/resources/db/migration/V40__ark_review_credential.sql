CREATE TABLE IF NOT EXISTS ark_review_credential (
    id              INTEGER PRIMARY KEY,
    access_key_enc  TEXT,
    secret_key_enc  TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ark_review_credential_singleton CHECK (id = 1)
);

COMMENT ON TABLE ark_review_credential IS '角色审核用的火山 Access Key / Secret Key，全环境只存一行，密文落库';
COMMENT ON COLUMN ark_review_credential.access_key_enc IS '加密后的 Access Key ID，不明文落库';
COMMENT ON COLUMN ark_review_credential.secret_key_enc IS '加密后的 Secret Access Key，不明文落库';
COMMENT ON COLUMN ark_review_credential.updated_at IS '最近一次在模型管理中保存的时间';
