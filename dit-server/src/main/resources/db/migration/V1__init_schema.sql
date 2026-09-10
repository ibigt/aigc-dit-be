-- =========================================
-- V1__init_schema.sql
-- 致启·光驭一期核心表（PostgreSQL）
-- =========================================

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    email           VARCHAR(128),
    display_name    VARCHAR(128) NOT NULL,
    password_hash   VARCHAR(128) NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    credit_balance  BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sys_user_username UNIQUE (username),
    CONSTRAINT uq_sys_user_email UNIQUE (email),
    CONSTRAINT ck_sys_user_role CHECK (role IN ('ADMIN', 'CREATOR')),
    CONSTRAINT ck_sys_user_status CHECK (status IN ('ENABLED', 'DISABLED'))
);

COMMENT ON TABLE sys_user IS '系统用户表（管理员 ADMIN / 创作者 CREATOR）';
COMMENT ON COLUMN sys_user.id IS '主键ID';
COMMENT ON COLUMN sys_user.username IS '登录账号（唯一；管理员为 admin，注册用户当前使用邮箱）';
COMMENT ON COLUMN sys_user.email IS '邮箱（唯一，可空）';
COMMENT ON COLUMN sys_user.display_name IS '显示名称';
COMMENT ON COLUMN sys_user.password_hash IS '密码哈希（BCrypt，不明文存储）';
COMMENT ON COLUMN sys_user.role IS '角色（ADMIN 管理员、CREATOR 创作者）';
COMMENT ON COLUMN sys_user.status IS '账号状态（ENABLED 启用、DISABLED 停用）';
COMMENT ON COLUMN sys_user.credit_balance IS '积分余额';
COMMENT ON COLUMN sys_user.created_at IS '创建时间';
COMMENT ON COLUMN sys_user.updated_at IS '更新时间';

-- ----------------------------
-- Table structure for ai_model
-- ----------------------------
CREATE TABLE IF NOT EXISTS ai_model (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    model_key       VARCHAR(128) NOT NULL,
    adapter_key     VARCHAR(64),
    provider        VARCHAR(64)  NOT NULL,
    endpoint        VARCHAR(512),
    api_key_enc     TEXT,
    category        VARCHAR(32)  NOT NULL,
    capabilities    JSONB,
    adapter_config  JSONB,
    pricing         JSONB,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    description     VARCHAR(512),
    badge           VARCHAR(64),
    logo            VARCHAR(256),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ai_model_key UNIQUE (model_key)
);

COMMENT ON TABLE ai_model IS '生成模型目录表（第三方 / 本地；创作端只读 enabled=true 的记录）';
COMMENT ON COLUMN ai_model.id IS '主键ID';
COMMENT ON COLUMN ai_model.name IS '模型展示名称';
COMMENT ON COLUMN ai_model.model_key IS '模型业务键（唯一，如 seedance-2.0）；不负责选翻译包';
COMMENT ON COLUMN ai_model.adapter_key IS '适配协议（出站翻译包）：gpt-image-2 / seedance-2 / minimax-h3；mock 为占位';
COMMENT ON COLUMN ai_model.provider IS '接入来源展示名（由 adapter_key 派生：RunningHub / ComfyUI / mock）';
COMMENT ON COLUMN ai_model.endpoint IS '调用地址（HTTP endpoint）';
COMMENT ON COLUMN ai_model.api_key_enc IS '加密后的厂商密钥，不明文落库';
COMMENT ON COLUMN ai_model.category IS '模型分类（image 图像、video 视频、tool 工具）';
COMMENT ON COLUMN ai_model.capabilities IS '模型能力（JSON 数组，如 ["video","t2v","r2v","i2v"]；r2v=参考生视频，i2v=首尾帧；创作端按当前类型与模式过滤）';
COMMENT ON COLUMN ai_model.adapter_config IS '协议附加配置 JSON（如 t2iPath、t2vPath、r2vPath、i2vPath）；出站字段映射写在对应 Adapter';
COMMENT ON COLUMN ai_model.pricing IS '计价 JSON（单位、销售积分、成本等）';
COMMENT ON COLUMN ai_model.enabled IS '是否启用（false 时创作端不可见）';
COMMENT ON COLUMN ai_model.description IS '模型描述';
COMMENT ON COLUMN ai_model.badge IS '列表角标文案（如 图像、视频）';
COMMENT ON COLUMN ai_model.logo IS '管理端展示用 Logo（相对路径、URL 或前端映射键）';
COMMENT ON COLUMN ai_model.created_at IS '创建时间';
COMMENT ON COLUMN ai_model.updated_at IS '更新时间';

-- ----------------------------
-- Table structure for gen_task
-- ----------------------------
CREATE TABLE IF NOT EXISTS gen_task (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES sys_user (id),
    model_id            BIGINT       REFERENCES ai_model (id),
    category            VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    progress            INTEGER      NOT NULL DEFAULT 0,
    error_message       TEXT,
    prompt              TEXT,
    params              JSONB,
    output_storage_key  VARCHAR(512),
    poster_key          VARCHAR(512),
    provider_job_id     VARCHAR(128),
    credit_cost         BIGINT       NOT NULL DEFAULT 0,
    copies              INTEGER      NOT NULL DEFAULT 1,
    output_keys         JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at         TIMESTAMPTZ,
    CONSTRAINT ck_gen_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_gen_task_progress CHECK (progress >= 0 AND progress <= 100),
    CONSTRAINT ck_gen_task_copies CHECK (copies >= 1 AND copies <= 4)
);

CREATE INDEX IF NOT EXISTS idx_gen_task_user_created ON gen_task (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gen_task_status ON gen_task (status);

COMMENT ON TABLE gen_task IS '异步生成任务表；仪表盘按本表聚合，不另做日志页';
COMMENT ON COLUMN gen_task.id IS '主键ID';
COMMENT ON COLUMN gen_task.user_id IS '提交用户ID（关联 sys_user.id）';
COMMENT ON COLUMN gen_task.model_id IS '所用模型ID（关联 ai_model.id，可空）';
COMMENT ON COLUMN gen_task.category IS '生成类别（image 图像、video 视频）';
COMMENT ON COLUMN gen_task.status IS '任务状态（PENDING、RUNNING、SUCCEEDED、FAILED、CANCELLED）';
COMMENT ON COLUMN gen_task.progress IS '进度百分比（0-100）';
COMMENT ON COLUMN gen_task.error_message IS '失败原因（status=FAILED 时记录）';
COMMENT ON COLUMN gen_task.prompt IS '提示词 / 生成指令';
COMMENT ON COLUMN gen_task.params IS '其余生成参数 JSON（分辨率、时长、参考图等）';
COMMENT ON COLUMN gen_task.output_storage_key IS '成片相对对象存储 key（库不存绝对 URL）';
COMMENT ON COLUMN gen_task.poster_key IS '封面相对对象存储 key（webp；列表展示用）';
COMMENT ON COLUMN gen_task.provider_job_id IS 'Provider 侧任务号（Mock / ComfyUI / 付费 API）';
COMMENT ON COLUMN gen_task.credit_cost IS '本次任务服务端计算的积分消耗（售价 × 参数倍率 × 副本，视频按秒计价时再乘时长）';
COMMENT ON COLUMN gen_task.copies IS '生成副本数（1-4）';
COMMENT ON COLUMN gen_task.output_keys IS '多份成片相对对象存储 key 数组；第一项可冗余到 output_storage_key';
COMMENT ON COLUMN gen_task.created_at IS '创建时间';
COMMENT ON COLUMN gen_task.updated_at IS '更新时间';
COMMENT ON COLUMN gen_task.finished_at IS '结束时间（成功、失败或取消）';

-- ----------------------------
-- Table structure for creation_session
-- ----------------------------
CREATE TABLE IF NOT EXISTS creation_session (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES sys_user (id),
    title       VARCHAR(256),
    category    VARCHAR(32),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_creation_session_user ON creation_session (user_id, created_at DESC);

COMMENT ON TABLE creation_session IS '快速创作会话表';
COMMENT ON COLUMN creation_session.id IS '主键ID';
COMMENT ON COLUMN creation_session.user_id IS '所属用户ID（关联 sys_user.id）';
COMMENT ON COLUMN creation_session.title IS '会话标题';
COMMENT ON COLUMN creation_session.category IS '会话类别（image 图像、video 视频）';
COMMENT ON COLUMN creation_session.created_at IS '创建时间';
COMMENT ON COLUMN creation_session.updated_at IS '更新时间';

-- ----------------------------
-- Table structure for creation
-- ----------------------------
CREATE TABLE IF NOT EXISTS creation (
    id            BIGSERIAL PRIMARY KEY,
    session_id    BIGINT       REFERENCES creation_session (id),
    user_id       BIGINT       NOT NULL REFERENCES sys_user (id),
    task_id       BIGINT       REFERENCES gen_task (id),
    model_id      BIGINT       REFERENCES ai_model (id),
    title         VARCHAR(256),
    category      VARCHAR(32)  NOT NULL,
    storage_key   VARCHAR(512),
    poster_key    VARCHAR(512),
    prompt        TEXT,
    ratio         VARCHAR(32),
    resolution    VARCHAR(32),
    duration_sec  INTEGER,
    credit_cost   BIGINT,
    copy_index    INTEGER      NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_creation_copy_index CHECK (copy_index >= 1 AND copy_index <= 4)
);

CREATE INDEX IF NOT EXISTS idx_creation_user ON creation (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_creation_session ON creation (session_id);
CREATE INDEX IF NOT EXISTS idx_creation_model ON creation (model_id);

COMMENT ON TABLE creation IS '作品表；网格列表用 poster_key，详情再播原片';
COMMENT ON COLUMN creation.id IS '主键ID';
COMMENT ON COLUMN creation.session_id IS '所属创作会话ID（关联 creation_session.id，可空）';
COMMENT ON COLUMN creation.user_id IS '所属用户ID（关联 sys_user.id）';
COMMENT ON COLUMN creation.task_id IS '来源生成任务ID（关联 gen_task.id，可空）';
COMMENT ON COLUMN creation.model_id IS '所用模型ID（关联 ai_model.id，可空）';
COMMENT ON COLUMN creation.title IS '作品标题';
COMMENT ON COLUMN creation.category IS '作品类别（image 图像、video 视频）';
COMMENT ON COLUMN creation.storage_key IS '原片相对对象存储 key';
COMMENT ON COLUMN creation.poster_key IS '封面相对对象存储 key（webp）';
COMMENT ON COLUMN creation.prompt IS '提示词（列表/详情展示，与任务 prompt 冗余）';
COMMENT ON COLUMN creation.ratio IS '画面比例（如 16:9、1:1）';
COMMENT ON COLUMN creation.resolution IS '分辨率档位（如 2K、1080P）';
COMMENT ON COLUMN creation.duration_sec IS '视频时长（秒）；图片为空';
COMMENT ON COLUMN creation.credit_cost IS '本条作品分摊的积分（通常为任务 credit_cost / copies）';
COMMENT ON COLUMN creation.copy_index IS '同一任务中的副本序号（从 1 起）';
COMMENT ON COLUMN creation.created_at IS '创建时间';

-- ----------------------------
-- Table structure for asset
-- ----------------------------
CREATE TABLE IF NOT EXISTS asset (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES sys_user (id),
    name         VARCHAR(256) NOT NULL,
    mime_type    VARCHAR(128),
    kind         VARCHAR(16),
    storage_key  VARCHAR(512) NOT NULL,
    poster_key   VARCHAR(512),
    size_bytes   BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_asset_kind CHECK (kind IS NULL OR kind IN ('image', 'video'))
);

CREATE INDEX IF NOT EXISTS idx_asset_user ON asset (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_asset_user_kind ON asset (user_id, kind);

COMMENT ON TABLE asset IS '用户素材库表';
COMMENT ON COLUMN asset.id IS '主键ID';
COMMENT ON COLUMN asset.user_id IS '所属用户ID（关联 sys_user.id）';
COMMENT ON COLUMN asset.name IS '素材名称';
COMMENT ON COLUMN asset.mime_type IS 'MIME 类型（如 image/png、video/mp4）';
COMMENT ON COLUMN asset.kind IS '素材种类（image 图像、video 视频）；由 mime_type 推导或上传时写入';
COMMENT ON COLUMN asset.storage_key IS '原文件相对对象存储 key';
COMMENT ON COLUMN asset.poster_key IS '封面相对对象存储 key（视频抽帧；图片可与原文件相同或为空）';
COMMENT ON COLUMN asset.size_bytes IS '文件大小（字节）';
COMMENT ON COLUMN asset.created_at IS '创建时间';

-- ----------------------------
-- Table structure for credit_ledger
-- ----------------------------
CREATE TABLE IF NOT EXISTS credit_ledger (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES sys_user (id),
    change_amt     BIGINT       NOT NULL,
    balance_after  BIGINT       NOT NULL,
    reason         VARCHAR(64)  NOT NULL,
    ref_id         VARCHAR(64),
    remark         VARCHAR(256),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_credit_ledger_user ON credit_ledger (user_id, created_at DESC);

COMMENT ON TABLE credit_ledger IS '积分流水表（充值 Mock / 生成扣减 / 退款 / 调整）';
COMMENT ON COLUMN credit_ledger.id IS '主键ID';
COMMENT ON COLUMN credit_ledger.user_id IS '所属用户ID（关联 sys_user.id）';
COMMENT ON COLUMN credit_ledger.change_amt IS '变动积分（正数为增加，负数为扣减）';
COMMENT ON COLUMN credit_ledger.balance_after IS '变动后积分余额';
COMMENT ON COLUMN credit_ledger.reason IS '变动原因（RECHARGE 充值、GENERATE 生成扣减、REFUND 退款、ADJUST 调整）';
COMMENT ON COLUMN credit_ledger.ref_id IS '关联业务ID（如 gen_task.id、支付单号）';
COMMENT ON COLUMN credit_ledger.remark IS '备注';
COMMENT ON COLUMN credit_ledger.created_at IS '创建时间';

-- ----------------------------
-- Init data: admin
-- password = Fnii@123（BCrypt）
-- ----------------------------
INSERT INTO sys_user (username, email, display_name, password_hash, role, status, credit_balance)
VALUES (
    'admin',
    'admin@localhost',
    '系统管理员',
    '$2a$10$fvBrrOgdtqRlpAFNypglTue/Fd6SX5lwoAtrqOJ8U.qzzdByyTU2a',
    'ADMIN',
    'ENABLED',
    100000
)
ON CONFLICT (username) DO NOTHING;

-- 首尾帧能力从 v2v 规范为 i2v（幂等；空库无匹配行）
UPDATE ai_model
SET capabilities = (
        SELECT COALESCE(jsonb_agg(to_jsonb(mapped) ORDER BY ord), '[]'::jsonb)
        FROM (
            SELECT MIN(ord) AS ord,
                   CASE WHEN val IN ('v2v', 'frames', 'frame') THEN 'i2v' ELSE val END AS mapped
            FROM jsonb_array_elements_text(capabilities) WITH ORDINALITY AS t(val, ord)
            GROUP BY CASE WHEN val IN ('v2v', 'frames', 'frame') THEN 'i2v' ELSE val END
        ) s
    ),
    updated_at = NOW()
WHERE capabilities IS NOT NULL
  AND jsonb_typeof(capabilities) = 'array'
  AND EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(capabilities) AS x(val)
        WHERE val IN ('v2v', 'frames', 'frame')
    );

UPDATE ai_model
SET adapter_config = (adapter_config - 'v2vPath')
        || jsonb_build_object('i2vPath', adapter_config -> 'v2vPath'),
    updated_at = NOW()
WHERE adapter_config ? 'v2vPath'
  AND NOT (adapter_config ? 'i2vPath');

UPDATE ai_model
SET adapter_config = adapter_config - 'v2vPath',
    updated_at = NOW()
WHERE adapter_config ? 'v2vPath';

UPDATE gen_task
SET params = jsonb_set(params, '{mode}', '"i2v"'::jsonb),
    updated_at = NOW()
WHERE params->>'mode' IN ('v2v', 'frames', 'frame');

-- 适配协议 adapter_key（已有库 CREATE IF NOT EXISTS 不会加列，这里幂等补齐）
ALTER TABLE ai_model ADD COLUMN IF NOT EXISTS adapter_key VARCHAR(64);
COMMENT ON COLUMN ai_model.adapter_key IS '适配协议（出站翻译包）：gpt-image-2 / seedance-2 / minimax-h3；mock 为占位';

UPDATE ai_model
SET adapter_key = 'minimax-h3', updated_at = NOW()
WHERE (adapter_key IS NULL OR adapter_key = '')
  AND category = 'video'
  AND (provider ILIKE '%comfy%' OR model_key ILIKE '%minimax%');

UPDATE ai_model
SET adapter_key = 'seedance-2', updated_at = NOW()
WHERE (adapter_key IS NULL OR adapter_key = '')
  AND category = 'video'
  AND (provider ILIKE '%running%' OR model_key ILIKE '%seedance%');

UPDATE ai_model
SET adapter_key = 'gpt-image-2', updated_at = NOW()
WHERE (adapter_key IS NULL OR adapter_key = '')
  AND category = 'image'
  AND (provider ILIKE '%running%' OR model_key ILIKE '%gpt-image%');

UPDATE ai_model
SET adapter_key = 'mock', updated_at = NOW()
WHERE (adapter_key IS NULL OR adapter_key = '')
  AND (provider ILIKE 'mock' OR model_key ILIKE 'mock%');

UPDATE ai_model SET provider = 'ComfyUI', updated_at = NOW()
WHERE adapter_key = 'minimax-h3' AND provider IS DISTINCT FROM 'ComfyUI';

UPDATE ai_model SET provider = 'RunningHub', updated_at = NOW()
WHERE adapter_key IN ('gpt-image-2', 'seedance-2') AND provider IS DISTINCT FROM 'RunningHub';

UPDATE ai_model SET provider = 'mock', updated_at = NOW()
WHERE adapter_key = 'mock' AND provider IS DISTINCT FROM 'mock';

-- ----------------------------
-- Table structure for drama_series
-- ----------------------------
CREATE TABLE IF NOT EXISTS drama_series (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT       NOT NULL REFERENCES sys_user (id),
    name                    VARCHAR(128) NOT NULL,
    style_key               VARCHAR(64)  NOT NULL DEFAULT 'anime-comic',
    style_prefix            VARCHAR(512),
    aspect_ratio            VARCHAR(16)  NOT NULL DEFAULT '16:9',
    default_resolution      VARCHAR(16)  NOT NULL DEFAULT '720P',
    default_duration_sec    INTEGER      NOT NULL DEFAULT 5,
    planned_episode_count   INTEGER      NOT NULL DEFAULT 1,
    text_model_id           BIGINT       REFERENCES ai_model (id),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_drama_series_aspect CHECK (aspect_ratio IN ('16:9', '9:16', '1:1')),
    CONSTRAINT ck_drama_series_resolution CHECK (default_resolution IN ('480P', '720P')),
    CONSTRAINT ck_drama_series_duration CHECK (default_duration_sec IN (5, 10, 15)),
    CONSTRAINT ck_drama_series_planned CHECK (planned_episode_count >= 1 AND planned_episode_count <= 99)
);

CREATE INDEX IF NOT EXISTS idx_drama_series_user ON drama_series (user_id, updated_at DESC);

COMMENT ON TABLE drama_series IS '漫剧系列（项目容器）；与快速创作会话分离';
COMMENT ON COLUMN drama_series.id IS '主键ID';
COMMENT ON COLUMN drama_series.user_id IS '所属用户ID（关联 sys_user.id）';
COMMENT ON COLUMN drama_series.name IS '系列名称（如 侦探社）';
COMMENT ON COLUMN drama_series.style_key IS '风格键（如 anime-comic）；UI 用图例卡片选择，不展示前缀文本';
COMMENT ON COLUMN drama_series.style_prefix IS '风格前缀（仅分镜编译注入，由 style_key 在服务端解析，不暴露编辑）';
COMMENT ON COLUMN drama_series.aspect_ratio IS '画幅（16:9 / 9:16 / 1:1）';
COMMENT ON COLUMN drama_series.default_resolution IS '默认清晰度（480P / 720P）；验收默认 720P';
COMMENT ON COLUMN drama_series.default_duration_sec IS '默认单镜时长秒（仅 5 / 10 / 15，对齐 MiniMax）';
COMMENT ON COLUMN drama_series.planned_episode_count IS '规划集数';
COMMENT ON COLUMN drama_series.text_model_id IS '绑定的文本模型（ai_model.id，category=text）；空表示未绑定，乙走规则引擎';
COMMENT ON COLUMN drama_series.created_at IS '创建时间';
COMMENT ON COLUMN drama_series.updated_at IS '更新时间';

-- ----------------------------
-- Table structure for drama_episode
-- ----------------------------
CREATE TABLE IF NOT EXISTS drama_episode (
    id                          BIGSERIAL PRIMARY KEY,
    series_id                   BIGINT       NOT NULL REFERENCES drama_series (id) ON DELETE CASCADE,
    user_id                     BIGINT       NOT NULL REFERENCES sys_user (id),
    episode_number              INTEGER      NOT NULL,
    name                        VARCHAR(128) NOT NULL,
    source_text                 TEXT,
    script                      JSONB,
    status                      VARCHAR(16)  NOT NULL DEFAULT '未开始',
    inherited_from_episode_id   BIGINT       REFERENCES drama_episode (id) ON DELETE SET NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_drama_episode_series_num UNIQUE (series_id, episode_number),
    CONSTRAINT ck_drama_episode_number CHECK (episode_number >= 1),
    CONSTRAINT ck_drama_episode_status CHECK (status IN ('未开始', '待确认', '已锁定', '已过期'))
);

CREATE INDEX IF NOT EXISTS idx_drama_episode_series ON drama_episode (series_id, episode_number);
CREATE INDEX IF NOT EXISTS idx_drama_episode_user ON drama_episode (user_id, updated_at DESC);

COMMENT ON TABLE drama_episode IS '漫剧剧集；跨集只继承锁定资产与风格，剧本/分镜/视频从零开始';
COMMENT ON COLUMN drama_episode.id IS '主键ID';
COMMENT ON COLUMN drama_episode.series_id IS '所属系列ID（关联 drama_series.id）';
COMMENT ON COLUMN drama_episode.user_id IS '所属用户ID（关联 sys_user.id）';
COMMENT ON COLUMN drama_episode.episode_number IS '集序号（从 1 起）';
COMMENT ON COLUMN drama_episode.name IS '集名称（如 第 1 集）';
COMMENT ON COLUMN drama_episode.source_text IS '导入原文或梗概';
COMMENT ON COLUMN drama_episode.script IS '分场剧本文档 JSON（ScriptDoc）；阶段 2 写入';
COMMENT ON COLUMN drama_episode.status IS '状态机：未开始 / 待确认 / 已锁定 / 已过期';
COMMENT ON COLUMN drama_episode.inherited_from_episode_id IS '继承来源集ID（下一集复制资产时记录）';
COMMENT ON COLUMN drama_episode.created_at IS '创建时间';
COMMENT ON COLUMN drama_episode.updated_at IS '更新时间';
