-- Seed the first platform-wide AI Gateway sales price catalog.
--
-- Prices are expressed in USD per one million tokens and become effective at
-- 2026-07-22 00:00:00 Asia/Shanghai. Existing operator-published prices win:
-- this seed never replaces a current active price version.

WITH seed (
    model,
    provider,
    display_name,
    input_price_per_1m,
    output_price_per_1m,
    cache_creation_input_price_per_1m,
    cache_read_input_price_per_1m
) AS (
    VALUES
        ('anthropic/claude-fable-5', 'CLAUDE', 'Claude Fable 5', 10.00000000, 50.00000000, 12.50000000, 1.00000000),
        ('claude-fable-5', 'CLAUDE', 'Claude Fable 5', 10.00000000, 50.00000000, 12.50000000, 1.00000000),

        ('deepseek-v4-flash', 'OPENAI', 'DeepSeek V4 Flash', 0.14000000, 0.28000000, 0.14000000, 0.00280000),
        ('deepseek-v4-pro', 'OPENAI', 'DeepSeek V4 Pro', 0.43500000, 0.87000000, 0.43500000, 0.00362500),

        ('glm-5', 'CLAUDE', 'GLM-5', 0.88680000, 3.25160000, 0.88680000, 0.22170000),
        ('glm-5.2', 'CLAUDE', 'GLM-5.2', 1.18240000, 4.13840000, 1.18240000, 0.29560000),

        ('gpt-4', 'OPENAI', 'GPT-4', 30.00000000, 60.00000000, 30.00000000, 30.00000000),
        ('gpt-4-turbo', 'OPENAI', 'GPT-4 Turbo', 10.00000000, 30.00000000, 10.00000000, 10.00000000),
        ('gpt-4-turbo-preview', 'OPENAI', 'GPT-4 Turbo Preview', 10.00000000, 30.00000000, 10.00000000, 10.00000000),
        ('gpt-4.1', 'OPENAI', 'GPT-4.1', 2.00000000, 8.00000000, 2.00000000, 0.50000000),
        ('gpt-4.1-mini', 'OPENAI', 'GPT-4.1 Mini', 0.40000000, 1.60000000, 0.40000000, 0.10000000),
        ('gpt-4.1-nano', 'OPENAI', 'GPT-4.1 Nano', 0.10000000, 0.40000000, 0.10000000, 0.02500000),
        ('gpt-4.5-preview', 'OPENAI', 'GPT-4.5 Preview', 75.00000000, 150.00000000, 75.00000000, 37.50000000),
        ('gpt-4o', 'OPENAI', 'GPT-4o', 2.50000000, 10.00000000, 2.50000000, 1.25000000),
        ('gpt-4o-2024-08-06', 'OPENAI', 'GPT-4o 2024-08-06', 2.50000000, 10.00000000, 2.50000000, 1.25000000),
        ('gpt-4o-2024-11-20', 'OPENAI', 'GPT-4o 2024-11-20', 2.50000000, 10.00000000, 2.50000000, 1.25000000),
        ('gpt-4o-mini', 'OPENAI', 'GPT-4o Mini', 0.15000000, 0.60000000, 0.15000000, 0.07500000),
        ('gpt-4o-mini-2024-07-18', 'OPENAI', 'GPT-4o Mini 2024-07-18', 0.15000000, 0.60000000, 0.15000000, 0.07500000),

        ('gpt-5', 'OPENAI', 'GPT-5', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5-2025-08-07', 'OPENAI', 'GPT-5 2025-08-07', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5-chat', 'OPENAI', 'GPT-5 Chat', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5-chat-latest', 'OPENAI', 'GPT-5 Chat Latest', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5-codex', 'OPENAI', 'GPT-5 Codex', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5-mini', 'OPENAI', 'GPT-5 Mini', 0.25000000, 2.00000000, 0.25000000, 0.02500000),
        ('gpt-5-mini-2025-08-07', 'OPENAI', 'GPT-5 Mini 2025-08-07', 0.25000000, 2.00000000, 0.25000000, 0.02500000),
        ('gpt-5-nano', 'OPENAI', 'GPT-5 Nano', 0.05000000, 0.40000000, 0.05000000, 0.00500000),
        ('gpt-5-nano-2025-08-07', 'OPENAI', 'GPT-5 Nano 2025-08-07', 0.05000000, 0.40000000, 0.05000000, 0.00500000),
        ('gpt-5-pro', 'OPENAI', 'GPT-5 Pro', 15.00000000, 120.00000000, 15.00000000, 15.00000000),
        ('gpt-5-pro-2025-10-06', 'OPENAI', 'GPT-5 Pro 2025-10-06', 15.00000000, 120.00000000, 15.00000000, 15.00000000),

        ('gpt-5.1', 'OPENAI', 'GPT-5.1', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5.1-2025-11-13', 'OPENAI', 'GPT-5.1 2025-11-13', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5.1-chat-latest', 'OPENAI', 'GPT-5.1 Chat Latest', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5.1-codex', 'OPENAI', 'GPT-5.1 Codex', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5.1-codex-max', 'OPENAI', 'GPT-5.1 Codex Max', 1.25000000, 10.00000000, 1.25000000, 0.12500000),
        ('gpt-5.1-codex-mini', 'OPENAI', 'GPT-5.1 Codex Mini', 0.25000000, 2.00000000, 0.25000000, 0.02500000),

        ('gpt-5.2', 'OPENAI', 'GPT-5.2', 1.75000000, 14.00000000, 1.75000000, 0.17500000),
        ('gpt-5.2-2025-12-11', 'OPENAI', 'GPT-5.2 2025-12-11', 1.75000000, 14.00000000, 1.75000000, 0.17500000),
        ('gpt-5.2-chat-latest', 'OPENAI', 'GPT-5.2 Chat Latest', 1.75000000, 14.00000000, 1.75000000, 0.17500000),
        ('gpt-5.2-codex', 'OPENAI', 'GPT-5.2 Codex', 1.75000000, 14.00000000, 1.75000000, 0.17500000),
        ('gpt-5.2-pro', 'OPENAI', 'GPT-5.2 Pro', 21.00000000, 168.00000000, 21.00000000, 21.00000000),
        ('gpt-5.2-pro-2025-12-11', 'OPENAI', 'GPT-5.2 Pro 2025-12-11', 21.00000000, 168.00000000, 21.00000000, 21.00000000),
        ('gpt-5.3-codex', 'OPENAI', 'GPT-5.3 Codex', 1.75000000, 14.00000000, 1.75000000, 0.17500000),
        ('gpt-5.4', 'OPENAI', 'GPT-5.4', 2.50000000, 15.00000000, 2.50000000, 0.25000000),
        ('gpt-5.4-2026-03-05', 'OPENAI', 'GPT-5.4 2026-03-05', 2.50000000, 15.00000000, 2.50000000, 0.25000000),
        ('gpt-5.4-mini', 'OPENAI', 'GPT-5.4 Mini', 0.75000000, 4.50000000, 0.75000000, 0.07500000),
        ('gpt-5.5', 'OPENAI', 'GPT-5.5', 5.00000000, 30.00000000, 5.00000000, 0.50000000),
        ('gpt-5.6', 'OPENAI', 'GPT-5.6', 5.00000000, 30.00000000, 6.25000000, 0.50000000),
        ('gpt-5.6-sol', 'OPENAI', 'GPT-5.6 Sol', 5.00000000, 30.00000000, 6.25000000, 0.50000000),
        ('gpt-5.6-terra', 'OPENAI', 'GPT-5.6 Terra', 2.50000000, 15.00000000, 3.12500000, 0.25000000),
        ('gpt-5.6-luna', 'OPENAI', 'GPT-5.6 Luna', 1.00000000, 6.00000000, 1.25000000, 0.10000000),

        ('kimi-k2.5', 'OPENAI', 'Kimi K2.5', 0.60000000, 3.00000000, 0.60000000, 0.10000000),
        ('kimi-k2.7-code', 'CLAUDE', 'Kimi K2.7 Code', 0.95000000, 4.00000000, 0.95000000, 0.19000000),
        ('kimi-k3', 'CLAUDE', 'Kimi K3', 3.00000000, 15.00000000, 3.00000000, 0.30000000),

        ('minimax-m2.5', 'OPENAI', 'MiniMax M2.5', 0.30000000, 1.20000000, 0.37500000, 0.03000000),
        ('minimax-m2.7-highspeed', 'CLAUDE', 'MiniMax M2.7 Highspeed', 0.60000000, 2.40000000, 0.37500000, 0.06000000),
        ('minimax-m3', 'CLAUDE', 'MiniMax M3', 0.60000000, 2.40000000, 0.60000000, 0.12000000),

        ('o1', 'OPENAI', 'o1', 15.00000000, 60.00000000, 15.00000000, 7.50000000),
        ('o1-mini', 'OPENAI', 'o1 Mini', 1.10000000, 4.40000000, 1.10000000, 0.55000000),
        ('o1-preview', 'OPENAI', 'o1 Preview', 15.00000000, 60.00000000, 15.00000000, 7.50000000),
        ('o1-pro', 'OPENAI', 'o1 Pro', 150.00000000, 600.00000000, 150.00000000, 150.00000000),
        ('o3', 'OPENAI', 'o3', 2.00000000, 8.00000000, 2.00000000, 0.50000000),
        ('o3-mini', 'OPENAI', 'o3 Mini', 1.10000000, 4.40000000, 1.10000000, 0.55000000),
        ('o3-pro', 'OPENAI', 'o3 Pro', 20.00000000, 80.00000000, 20.00000000, 20.00000000),
        ('o4-mini', 'OPENAI', 'o4 Mini', 1.10000000, 4.40000000, 1.10000000, 0.27500000),

        ('qwen3-max', 'OPENAI', 'Qwen3 Max', 1.03460000, 4.13840000, 1.29325000, 0.10346000)
)
INSERT INTO public.ai_gateway_model_price_versions (
    model,
    normalized_model,
    provider,
    display_name,
    currency,
    input_price_per_1m,
    output_price_per_1m,
    cache_creation_input_price_per_1m,
    cache_read_input_price_per_1m,
    effective_from,
    is_active
)
SELECT
    model,
    lower(btrim(model)),
    provider,
    display_name,
    'USD',
    input_price_per_1m,
    output_price_per_1m,
    cache_creation_input_price_per_1m,
    cache_read_input_price_per_1m,
    TIMESTAMPTZ '2026-07-22 00:00:00+08',
    TRUE
FROM seed
ON CONFLICT (normalized_model, currency)
    WHERE is_active = TRUE AND effective_to IS NULL
DO NOTHING;
