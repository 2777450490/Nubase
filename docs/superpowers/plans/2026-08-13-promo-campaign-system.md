# 运营推广活动管理系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 nubase MCP 工具创建一个简单报名型营销活动管理系统,包含活动管理、用户报名、统计看板。

**Architecture:** 纯 PostgREST + SQL RPC 函数方案。通过 `executeSql` 创建 2 张表 + RLS 策略 + 3 个统计函数 + 种子数据,通过 `listTables` / `getTableStructure` / `exportRlsPolicies` 验证。不使用 Edge Function。

**Tech Stack:** PostgreSQL 15 / nubase MCP 工具 (executeSql, executeSqlDryRun, listTables, getTableStructure, exportRlsPolicies)

**Spec:** `docs/superpowers/specs/2026-08-13-promo-campaign-system-design.md`

---

## Database Objects Overview

| 对象 | 类型 | 说明 |
|------|------|------|
| `public.campaigns` | TABLE | 活动表 |
| `public.participations` | TABLE | 报名记录表 |
| `public.set_updated_at()` | FUNCTION | 触发器函数,自动更新 updated_at |
| `public.dashboard_summary()` | FUNCTION (RPC) | 看板总览统计 |
| `public.participation_trend(BIGINT)` | FUNCTION (RPC) | 按日参与趋势 |
| `public.campaign_participation_counts()` | FUNCTION (RPC) | 各活动参与明细 |

---

### Task 1: Pre-flight Check — 验证干净环境

**Tools:** `listTables`, `executeSqlDryRun`

- [ ] **Step 1: 检查现有表**

Call `listTables` with `schemas: "public"`

Expected: 返回当前 public schema 的表列表。确认不存在 `campaigns` 和 `participations` 表。如果已存在,需先 DROP 或跳过建表步骤。

- [ ] **Step 2: Dry run 完整 schema SQL**

Call `executeSqlDryRun` with `sqlQuery`:

```sql
CREATE TABLE public.campaigns (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'active', 'closed')),
    start_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    end_time TIMESTAMPTZ,
    max_participants INTEGER,
    reward_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.participations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES public.campaigns(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    participated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(campaign_id, user_id)
);

CREATE INDEX idx_participations_campaign_id ON public.participations(campaign_id);
CREATE INDEX idx_participations_user_id ON public.participations(user_id);
CREATE INDEX idx_campaigns_status ON public.campaigns(status);

CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER campaigns_set_updated_at
    BEFORE UPDATE ON public.campaigns
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.participations ENABLE ROW LEVEL SECURITY;

CREATE POLICY campaigns_select_all ON public.campaigns
    FOR SELECT TO anon, authenticated USING (true);

CREATE POLICY campaigns_write_admin ON public.campaigns
    FOR ALL TO service_role USING (true) WITH CHECK (true);

CREATE POLICY participations_select_own ON public.participations
    FOR SELECT TO authenticated USING (user_id = auth.uid());

CREATE POLICY participations_insert_own ON public.participations
    FOR INSERT TO authenticated WITH CHECK (user_id = auth.uid());

CREATE POLICY participations_all_admin ON public.participations
    FOR ALL TO service_role USING (true) WITH CHECK (true);

CREATE OR REPLACE FUNCTION public.dashboard_summary()
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    total_campaigns BIGINT;
    active_campaigns BIGINT;
    total_participations BIGINT;
    today_participations BIGINT;
BEGIN
    SELECT COUNT(*) INTO total_campaigns FROM public.campaigns;
    SELECT COUNT(*) INTO active_campaigns FROM public.campaigns WHERE status = 'active';
    SELECT COUNT(*) INTO total_participations FROM public.participations;
    SELECT COUNT(*) INTO today_participations
    FROM public.participations
    WHERE participated_at >= date_trunc('day', now());

    RETURN json_build_object(
        'total_campaigns', total_campaigns,
        'active_campaigns', active_campaigns,
        'total_participations', total_participations,
        'today_participations', today_participations
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.participation_trend(p_campaign_id BIGINT DEFAULT NULL)
RETURNS TABLE(day DATE, count BIGINT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF p_campaign_id IS NULL THEN
        RETURN QUERY
        SELECT date_trunc('day', p.participated_at)::DATE AS day,
               COUNT(*)::BIGINT AS count
        FROM public.participations p
        GROUP BY 1
        ORDER BY 1;
    ELSE
        RETURN QUERY
        SELECT date_trunc('day', p.participated_at)::DATE AS day,
               COUNT(*)::BIGINT AS count
        FROM public.participations p
        WHERE p.campaign_id = p_campaign_id
        GROUP BY 1
        ORDER BY 1;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.campaign_participation_counts()
RETURNS TABLE(campaign_id BIGINT, name TEXT, status TEXT, participation_count BIGINT, max_participants INTEGER)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT c.id AS campaign_id,
           c.name,
           c.status,
           COUNT(p.id)::BIGINT AS participation_count,
           c.max_participants
    FROM public.campaigns c
    LEFT JOIN public.participations p ON p.campaign_id = c.id
    GROUP BY c.id, c.name, c.status, c.max_participants
    ORDER BY participation_count DESC;
END;
$$;
```

Expected: 返回语句数和风险评估,不实际执行。确认无语法错误。

---

### Task 2: Create Tables + Indexes + Trigger

**Tools:** `executeSql`

- [ ] **Step 1: 创建 campaigns 和 participations 表**

Call `executeSql` with `sqlQuery`:

```sql
CREATE TABLE public.campaigns (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'active', 'closed')),
    start_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    end_time TIMESTAMPTZ,
    max_participants INTEGER,
    reward_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.participations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES public.campaigns(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    participated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(campaign_id, user_id)
);
```

Expected: 两张表创建成功。

- [ ] **Step 2: 创建索引**

Call `executeSql` with `sqlQuery`:

```sql
CREATE INDEX idx_participations_campaign_id ON public.participations(campaign_id);
CREATE INDEX idx_participations_user_id ON public.participations(user_id);
CREATE INDEX idx_campaigns_status ON public.campaigns(status);
```

Expected: 3 个索引创建成功。

- [ ] **Step 3: 创建 updated_at 触发器函数和触发器**

Call `executeSql` with `sqlQuery`:

```sql
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER campaigns_set_updated_at
    BEFORE UPDATE ON public.campaigns
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
```

Expected: 函数和触发器创建成功。

---

### Task 3: Enable RLS + Create Policies

**Tools:** `executeSql`

- [ ] **Step 1: 启用 RLS 并创建 5 条策略**

Call `executeSql` with `sqlQuery`:

```sql
ALTER TABLE public.campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.participations ENABLE ROW LEVEL SECURITY;

CREATE POLICY campaigns_select_all ON public.campaigns
    FOR SELECT TO anon, authenticated USING (true);

CREATE POLICY campaigns_write_admin ON public.campaigns
    FOR ALL TO service_role USING (true) WITH CHECK (true);

CREATE POLICY participations_select_own ON public.participations
    FOR SELECT TO authenticated USING (user_id = auth.uid());

CREATE POLICY participations_insert_own ON public.participations
    FOR INSERT TO authenticated WITH CHECK (user_id = auth.uid());

CREATE POLICY participations_all_admin ON public.participations
    FOR ALL TO service_role USING (true) WITH CHECK (true);
```

Expected: RLS 启用,5 条策略创建成功。

---

### Task 4: Create RPC Functions

**Tools:** `executeSql`

- [ ] **Step 1: 创建 dashboard_summary() 函数**

Call `executeSql` with `sqlQuery`:

```sql
CREATE OR REPLACE FUNCTION public.dashboard_summary()
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    total_campaigns BIGINT;
    active_campaigns BIGINT;
    total_participations BIGINT;
    today_participations BIGINT;
BEGIN
    SELECT COUNT(*) INTO total_campaigns FROM public.campaigns;
    SELECT COUNT(*) INTO active_campaigns FROM public.campaigns WHERE status = 'active';
    SELECT COUNT(*) INTO total_participations FROM public.participations;
    SELECT COUNT(*) INTO today_participations
    FROM public.participations
    WHERE participated_at >= date_trunc('day', now());

    RETURN json_build_object(
        'total_campaigns', total_campaigns,
        'active_campaigns', active_campaigns,
        'total_participations', total_participations,
        'today_participations', today_participations
    );
END;
$$;
```

Expected: 函数创建成功。

- [ ] **Step 2: 创建 participation_trend() 函数**

Call `executeSql` with `sqlQuery`:

```sql
CREATE OR REPLACE FUNCTION public.participation_trend(p_campaign_id BIGINT DEFAULT NULL)
RETURNS TABLE(day DATE, count BIGINT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF p_campaign_id IS NULL THEN
        RETURN QUERY
        SELECT date_trunc('day', p.participated_at)::DATE AS day,
               COUNT(*)::BIGINT AS count
        FROM public.participations p
        GROUP BY 1
        ORDER BY 1;
    ELSE
        RETURN QUERY
        SELECT date_trunc('day', p.participated_at)::DATE AS day,
               COUNT(*)::BIGINT AS count
        FROM public.participations p
        WHERE p.campaign_id = p_campaign_id
        GROUP BY 1
        ORDER BY 1;
    END IF;
END;
$$;
```

Expected: 函数创建成功。

- [ ] **Step 3: 创建 campaign_participation_counts() 函数**

Call `executeSql` with `sqlQuery`:

```sql
CREATE OR REPLACE FUNCTION public.campaign_participation_counts()
RETURNS TABLE(campaign_id BIGINT, name TEXT, status TEXT, participation_count BIGINT, max_participants INTEGER)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT c.id AS campaign_id,
           c.name,
           c.status,
           COUNT(p.id)::BIGINT AS participation_count,
           c.max_participants
    FROM public.campaigns c
    LEFT JOIN public.participations p ON p.campaign_id = c.id
    GROUP BY c.id, c.name, c.status, c.max_participants
    ORDER BY participation_count DESC;
END;
$$;
```

Expected: 函数创建成功。

---

### Task 5: Seed Demo Data

**Tools:** `executeSql`

- [ ] **Step 1: 插入 3 个活动**

Call `executeSql` with `sqlQuery`:

```sql
INSERT INTO public.campaigns (name, description, status, start_time, end_time, max_participants, reward_config) VALUES
('夏季大促', '夏季限时促销活动，参与即享优惠', 'active', '2026-08-01T00:00:00+08', '2026-08-31T23:59:59+08', 500, '{"type": "discount", "value": 0.8}'::jsonb),
('新品体验官招募', '报名成为新品体验官，抢先试用未发布产品', 'active', '2026-08-10T00:00:00+08', NULL, NULL, '{"type": "product_trial"}'::jsonb),
('内测反馈有奖', '参与内测并提交反馈即可获得奖励', 'closed', '2026-07-01T00:00:00+08', '2026-07-31T23:59:59+08', 100, '{"type": "points", "value": 500}'::jsonb);
```

Expected: 3 行插入成功。

- [ ] **Step 2: 插入 15 条报名记录**

Call `executeSql` with `sqlQuery`:

```sql
INSERT INTO public.participations (campaign_id, user_id, participated_at, metadata) VALUES
(1, gen_random_uuid(), now() - interval '4 days', '{"source": "web"}'::jsonb),
(1, gen_random_uuid(), now() - interval '4 days', '{"source": "app"}'::jsonb),
(1, gen_random_uuid(), now() - interval '3 days', '{"source": "web"}'::jsonb),
(1, gen_random_uuid(), now() - interval '3 days', '{"source": "web"}'::jsonb),
(1, gen_random_uuid(), now() - interval '2 days', '{"source": "app"}'::jsonb),
(1, gen_random_uuid(), now() - interval '1 day', '{"source": "web"}'::jsonb),
(1, gen_random_uuid(), now() - interval '1 day', '{"source": "share"}'::jsonb),
(1, gen_random_uuid(), now(), '{"source": "web"}'::jsonb),
(2, gen_random_uuid(), now() - interval '3 days', '{"source": "web"}'::jsonb),
(2, gen_random_uuid(), now() - interval '2 days', '{"source": "app"}'::jsonb),
(2, gen_random_uuid(), now() - interval '2 days', '{"source": "web"}'::jsonb),
(2, gen_random_uuid(), now() - interval '1 day', '{"source": "share"}'::jsonb),
(2, gen_random_uuid(), now(), '{"source": "web"}'::jsonb),
(3, gen_random_uuid(), now() - interval '5 days', '{"source": "web"}'::jsonb),
(3, gen_random_uuid(), now() - interval '5 days', '{"source": "app"}'::jsonb);
```

Expected: 15 行插入成功。分布: 活动1=8条, 活动2=5条, 活动3=2条。

---

### Task 6: Verify Schema

**Tools:** `listTables`, `getTableStructure`, `exportRlsPolicies`

- [ ] **Step 1: 验证表已创建**

Call `listTables` with `schemas: "public"`

Expected: 返回列表中包含 `campaigns` 和 `participations` 表。

- [ ] **Step 2: 验证 campaigns 表结构**

Call `getTableStructure` with `tableName: "campaigns"`, `schema: "public"`

Expected: 返回 10 列 (id, name, description, status, start_time, end_time, max_participants, reward_config, created_at, updated_at),包含 CHECK 约束和 UNIQUE 约束信息。

- [ ] **Step 3: 验证 participations 表结构**

Call `getTableStructure` with `tableName: "participations"`, `schema: "public"`

Expected: 返回 5 列 (id, campaign_id, user_id, participated_at, metadata),包含 FK 约束和 UNIQUE(campaign_id, user_id) 约束。

- [ ] **Step 4: 验证 RLS 策略**

Call `exportRlsPolicies` with `schemaName: "public"`, `tableNames: "campaigns,participations"`, `includeDropStatements: false`, `groupBySchema: true`

Expected: 返回 5 条策略 (campaigns_select_all, campaigns_write_admin, participations_select_own, participations_insert_own, participations_all_admin)。

---

### Task 7: Test RPC Functions

**Tools:** `executeSql`

- [ ] **Step 1: 测试 dashboard_summary()**

Call `executeSql` with `sqlQuery`:

```sql
SELECT public.dashboard_summary();
```

Expected: 返回 JSON,如 `{"total_campaigns": 3, "active_campaigns": 2, "total_participations": 15, "today_participations": 3}`

- [ ] **Step 2: 测试 participation_trend(NULL) — 全部活动趋势**

Call `executeSql` with `sqlQuery`:

```sql
SELECT * FROM public.participation_trend(NULL);
```

Expected: 返回 ~5 行 (最近5天),每行包含 day 和 count。

- [ ] **Step 3: 测试 participation_trend(1) — 单个活动趋势**

Call `executeSql` with `sqlQuery`:

```sql
SELECT * FROM public.participation_trend(1);
```

Expected: 返回 ~5 行,仅活动 1 (夏季大促) 的按日参与数据。

- [ ] **Step 4: 测试 campaign_participation_counts()**

Call `executeSql` with `sqlQuery`:

```sql
SELECT * FROM public.campaign_participation_counts();
```

Expected: 返回 3 行:
- 活动1 (夏季大促): participation_count=8, max_participants=500
- 活动2 (新品体验官招募): participation_count=5, max_participants=NULL
- 活动3 (内测反馈有奖): participation_count=2, max_participants=100

- [ ] **Step 5: 验证 updated_at 触发器**

Call `executeSql` with `sqlQuery`:

```sql
UPDATE public.campaigns SET name = '夏季大促（更新测试）' WHERE id = 1;
SELECT name, updated_at FROM public.campaigns WHERE id = 1;
UPDATE public.campaigns SET name = '夏季大促' WHERE id = 1;
```

Expected: updated_at 时间戳大于 created_at,证明触发器生效。最后恢复原名。

---

## Self-Review

**1. Spec coverage:**
- campaigns 表 (10 列) → Task 2 Step 1 ✓
- participations 表 (5 列 + UNIQUE) → Task 2 Step 1 ✓
- 3 个索引 → Task 2 Step 2 ✓
- updated_at 触发器 → Task 2 Step 3 ✓
- 5 条 RLS 策略 → Task 3 Step 1 ✓
- dashboard_summary() → Task 4 Step 1 ✓
- participation_trend() → Task 4 Step 2 ✓
- campaign_participation_counts() → Task 4 Step 3 ✓
- 3 个活动种子数据 → Task 5 Step 1 ✓
- 15 条报名种子数据 → Task 5 Step 2 ✓
- listTables 验证 → Task 6 Step 1 ✓
- getTableStructure 验证 → Task 6 Step 2-3 ✓
- exportRlsPolicies 验证 → Task 6 Step 4 ✓
- RPC 函数测试 → Task 7 Steps 1-4 ✓
- 触发器测试 → Task 7 Step 5 ✓

**2. Placeholder scan:** 无 TBD/TODO,所有 SQL 均为完整可执行内容。✓

**3. Type consistency:**
- `participation_trend` 参数名 `p_campaign_id` 在 Task 1 dry run 和 Task 4 Step 2 中一致 ✓
- `dashboard_summary` 返回 JSON 字段名在 Task 4 和 Task 7 中一致 ✓
- `campaign_participation_counts` 返回列名在 Task 4 和 Task 7 中一致 ✓
- 种子数据 campaign_id 1/2/3 与 Task 7 期望值的活动名称对应 ✓
