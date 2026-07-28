-- 将 AI 网关中央计费表及字段的数据库注释统一为中文。
-- V10 已经执行，不能直接修改其内容，否则会导致 Flyway 校验和不一致。

COMMENT ON TABLE public.ai_gateway_billing_accounts IS
    'AI 网关项目计费账户，保存余额、信用额度和活动预占金额，是项目可用额度的权威事实源';

COMMENT ON COLUMN public.ai_gateway_billing_accounts.id IS '计费账户主键';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.app_code IS '项目唯一编码';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.currency IS '账户币种，例如 USD';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.balance IS '账户当前余额，结算时从该金额扣减';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.reserved_amount IS '已预占但尚未结算或释放的金额';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.credit_limit IS '允许项目使用的信用额度';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.status IS '账户状态：ACTIVE、SUSPENDED、CLOSED';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.version IS '账户变更版本号，每次余额或预占变化时递增';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.created_at IS '账户创建时间';
COMMENT ON COLUMN public.ai_gateway_billing_accounts.updated_at IS '账户最后更新时间';

COMMENT ON TABLE public.ai_gateway_model_price_versions IS
    'AI 模型平台售价版本表，新请求按当前有效价格预占，历史请求使用自身价格快照结算';

COMMENT ON COLUMN public.ai_gateway_model_price_versions.id IS '模型价格版本主键';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.model IS '对外暴露的原始模型名称';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.normalized_model IS '用于价格匹配的规范化模型名称';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.provider IS '模型协议提供方，例如 OPENAI 或 CLAUDE';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.display_name IS '模型展示名称';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.currency IS '价格币种，例如 USD';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.input_price_per_1m IS '每一百万普通输入 Token 的售价';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.output_price_per_1m IS '每一百万输出 Token 的售价';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.cache_creation_input_price_per_1m IS
    '每一百万缓存创建输入 Token 的售价';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.cache_read_input_price_per_1m IS
    '每一百万缓存读取输入 Token 的售价';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.effective_from IS '价格版本生效时间';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.effective_to IS '价格版本失效时间，空值表示尚未结束';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.is_active IS '价格版本是否处于活动状态';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.created_at IS '价格版本创建时间';
COMMENT ON COLUMN public.ai_gateway_model_price_versions.created_by IS '发布该价格版本的平台操作人 ID';

COMMENT ON TABLE public.ai_gateway_billing_requests IS
    'AI 网关可计费请求状态表，保存项目、模型、Token、价格快照、预占金额和实际结算金额';

COMMENT ON COLUMN public.ai_gateway_billing_requests.request_id IS '网关请求唯一 ID，贯穿请求日志、用量统计和账务流水';
COMMENT ON COLUMN public.ai_gateway_billing_requests.account_id IS '关联的项目计费账户 ID';
COMMENT ON COLUMN public.ai_gateway_billing_requests.app_code IS '请求所属项目编码';
COMMENT ON COLUMN public.ai_gateway_billing_requests.client_idempotency_key IS '客户端幂等键，同一项目内唯一';
COMMENT ON COLUMN public.ai_gateway_billing_requests.model IS '客户端请求的原始模型名称';
COMMENT ON COLUMN public.ai_gateway_billing_requests.normalized_model IS '用于价格匹配的规范化模型名称';
COMMENT ON COLUMN public.ai_gateway_billing_requests.provider IS '价格版本记录的协议提供方';
COMMENT ON COLUMN public.ai_gateway_billing_requests.endpoint IS '客户端调用的网关端点';
COMMENT ON COLUMN public.ai_gateway_billing_requests.status IS
    '请求账务状态：RESERVED、SETTLED、RELEASED、RECONCILE_REQUIRED';
COMMENT ON COLUMN public.ai_gateway_billing_requests.currency IS '本次请求的结算币种';
COMMENT ON COLUMN public.ai_gateway_billing_requests.price_version_id IS '本次请求使用的模型价格版本 ID';
COMMENT ON COLUMN public.ai_gateway_billing_requests.input_price_per_1m_snapshot IS
    '本次请求的每百万普通输入 Token 价格快照';
COMMENT ON COLUMN public.ai_gateway_billing_requests.output_price_per_1m_snapshot IS
    '本次请求的每百万输出 Token 价格快照';
COMMENT ON COLUMN public.ai_gateway_billing_requests.cache_creation_price_per_1m_snapshot IS
    '本次请求的每百万缓存创建输入 Token 价格快照';
COMMENT ON COLUMN public.ai_gateway_billing_requests.cache_read_price_per_1m_snapshot IS
    '本次请求的每百万缓存读取输入 Token 价格快照';
COMMENT ON COLUMN public.ai_gateway_billing_requests.estimated_input_tokens IS '准入阶段估算的输入 Token 数';
COMMENT ON COLUMN public.ai_gateway_billing_requests.reserved_output_tokens IS '准入阶段用于预占的最大输出 Token 数';
COMMENT ON COLUMN public.ai_gateway_billing_requests.input_tokens IS '结算时记录的普通输入 Token 数';
COMMENT ON COLUMN public.ai_gateway_billing_requests.output_tokens IS '结算时记录的输出 Token 数';
COMMENT ON COLUMN public.ai_gateway_billing_requests.cache_creation_input_tokens IS
    '结算时记录的缓存创建输入 Token 数';
COMMENT ON COLUMN public.ai_gateway_billing_requests.cache_read_input_tokens IS
    '结算时记录的缓存读取输入 Token 数';
COMMENT ON COLUMN public.ai_gateway_billing_requests.reserved_amount IS '请求进入上游前预占的金额';
COMMENT ON COLUMN public.ai_gateway_billing_requests.actual_amount IS '根据上游实际 Token 用量计算的结算金额';
COMMENT ON COLUMN public.ai_gateway_billing_requests.usage_source IS '结算 Token 的来源，例如 UPSTREAM、LOCAL_ESTIMATE、MANUAL';
COMMENT ON COLUMN public.ai_gateway_billing_requests.error_code IS '账务异常或待对账原因的稳定错误码';
COMMENT ON COLUMN public.ai_gateway_billing_requests.created_at IS '请求账单创建时间';
COMMENT ON COLUMN public.ai_gateway_billing_requests.updated_at IS '请求账单最后更新时间';
COMMENT ON COLUMN public.ai_gateway_billing_requests.settled_at IS '请求完成结算的时间';

COMMENT ON TABLE public.ai_gateway_billing_ledger IS
    'AI 网关中央财务流水，追加记录余额和预占金额变化，用于审计、对账和幂等控制';

COMMENT ON COLUMN public.ai_gateway_billing_ledger.id IS '财务流水主键';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.account_id IS '关联的项目计费账户 ID';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.request_id IS '关联的网关请求 ID，充值或独立调账时可以为空';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.entry_type IS
    '流水类型：RESERVE、SETTLE、RELEASE、TOP_UP、ADJUSTMENT、REFUND';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.idempotency_key IS '流水全局幂等键，防止重复记账';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.balance_delta IS '本次操作产生的余额变化量';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.reserved_delta IS '本次操作产生的预占金额变化量';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.balance_after IS '本次操作完成后的账户余额';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.reserved_after IS '本次操作完成后的账户预占金额';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.currency IS '流水币种';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.reason IS '充值、调账、预占、结算或释放原因';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.created_at IS '流水创建时间';
COMMENT ON COLUMN public.ai_gateway_billing_ledger.created_by IS '创建该流水的平台操作人 ID，自动账务操作可以为空';
