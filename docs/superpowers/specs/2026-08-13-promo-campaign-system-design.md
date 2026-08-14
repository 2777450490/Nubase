# 运营推广活动管理系统 — 设计文档

> 日期: 2026-08-13
> 方案: PostgREST + SQL 视图/函数 (方案 A)
> 实施工具: nubase MCP 工具 (executeSql / executeSqlDryRun / listTables / exportRlsPolicies)

## 1. 概述

### 1.1 目标

构建一个简单报名型营销活动管理系统,具备:
- 活动管理(CRUD)
- 用户报名参与
- 基础统计看板(活动总数、进行中数、总参与数、今日参与数、按日趋势、各活动明细)

### 1.2 方案选择

采用 **PostgREST + SQL RPC 函数** 方案,不使用 Edge Function:
- CRUD 操作通过 nubase 内置 PostgREST REST API (`/rest/v1/*`) 完成
- 统计看板通过 PostgreSQL RPC 函数实现
- 行级安全 (RLS) 控制访问权限
- 全部 schema 通过 nubase MCP `executeSql` 工具创建

### 1.3 不包含的功能

- 定时任务(活动过期自动关闭)— 手动管理状态
- 每日数据汇总表 — 实时聚合查询即可
- 活动 Banner 图片上传 — 不使用 Storage
- Edge Function — 不需要

## 2. 架构与数据流

### 2.1 架构

```
                         nubase MCP 工具 (实施阶段)
                    ┌──────────────────────────────────┐
                    │  executeSqlDryRun → 预览 SQL 风险  │
                    │  executeSql → 建表/RLS/函数/种子数据 │
                    │  listTables → 验证 schema          │
                    │  exportRlsPolicies → 验证安全策略    │
                    └──────────────┬───────────────────┘
                                   │ 创建
                                   ▼
┌─────────────────────────────────────────────────────────────┐
│                  Project Database (PostgreSQL)               │
│                                                              │
│  public.campaigns                                           │
│  public.participations                                      │
│  public.set_updated_at()        — 触发器函数                  │
│  public.dashboard_summary()     — RPC: 看板总览               │
│  public.participation_trend()   — RPC: 参与趋势               │
│  public.campaign_participation_counts() — RPC: 各活动明细     │
└──────────────────────────────┬──────────────────────────────┘
                               │
                    PostgREST REST API (/rest/v1/*)
                               │
           ┌───────────────────┼───────────────────┐
           ▼                   ▼                   ▼
    运营管理端             用户端               数据看板
  POST /campaigns      POST /participations   POST /rpc/dashboard_summary
  GET  /campaigns      GET  /participations   POST /rpc/participation_trend
  PATCH /campaigns     (仅自己)               POST /rpc/campaign_participation_counts
```

### 2.2 数据流

| 场景 | 调用方 | 请求 | 数据流 |
|------|--------|------|--------|
| 创建活动 | 运营 (service_role) | `POST /rest/v1/campaigns` | → campaigns 表 |
| 浏览活动 | 任意 (anon) | `GET /rest/v1/campaigns?status=eq.active` | ← campaigns 表 |
| 用户报名 | 已登录用户 (authenticated) | `POST /rest/v1/participations` | → participations 表 |
| 查看我的报名 | 已登录用户 | `GET /rest/v1/participations` | ← participations 表 (RLS 过滤) |
| 看板总览 | 运营 | `POST /rest/v1/rpc/dashboard_summary` | ← 聚合查询 |
| 参与趋势 | 运营 | `POST /rest/v1/rpc/participation_trend` | ← 按日聚合 |
| 各活动明细 | 运营 | `POST /rest/v1/rpc/campaign_participation_counts` | ← JOIN 聚合 |

### 2.3 双令牌模型

所有请求携带两层令牌 (nubase 标准模型):
- `apikey: <project token>` — 标识项目 + 基础角色 (anon/authenticated/service_role)
- `Authorization: Bearer <jwt>` — 标识终端用户,用于 RLS 的 `auth.uid()` 解析

## 3. 数据模型

### 3.1 campaigns 表

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | `BIGINT` | `GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | 自增主键 |
| `name` | `TEXT` | `NOT NULL` | 活动名称 |
| `description` | `TEXT` | `NOT NULL DEFAULT ''` | 活动描述 |
| `status` | `TEXT` | `NOT NULL DEFAULT 'draft'`, `CHECK IN ('draft','active','closed')` | 活动状态 |
| `start_time` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | 开始时间 |
| `end_time` | `TIMESTAMPTZ` | 可空 | 结束时间 (NULL = 长期) |
| `max_participants` | `INTEGER` | 可空 | 人数上限 (NULL = 不限) |
| `reward_config` | `JSONB` | `NOT NULL DEFAULT '{}'` | 奖励规则配置 |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | 创建时间 |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | 更新时间 (触发器自动更新) |

### 3.2 participations 表

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | `BIGINT` | `GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | 自增主键 |
| `campaign_id` | `BIGINT` | `NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE` | 关联活动 |
| `user_id` | `UUID` | `NOT NULL` | 报名用户 (对应 auth.users.id) |
| `participated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | 报名时间 |
| `metadata` | `JSONB` | `NOT NULL DEFAULT '{}'` | 附加信息 (来源渠道等) |

唯一约束: `UNIQUE(campaign_id, user_id)` — 每人每活动仅可报名一次。

### 3.3 索引

| 索引名 | 表 | 列 | 用途 |
|--------|-----|-----|------|
| `idx_participations_campaign_id` | participations | `campaign_id` | 按活动统计参与数 |
| `idx_participations_user_id` | participations | `user_id` | RLS 过滤用户自己的报名 |
| `idx_campaigns_status` | campaigns | `status` | 筛选进行中的活动 |

### 3.4 updated_at 触发器

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

## 4. RLS 行级安全策略

### 4.1 campaigns 表

| 策略名 | 角色 | 操作 | 规则 |
|--------|------|------|------|
| `campaigns_select_all` | `anon`, `authenticated` | SELECT | 允许所有人查看活动 (公开) |
| `campaigns_write_admin` | `service_role` | INSERT, UPDATE, DELETE | 仅管理员可增删改活动 |

### 4.2 participations 表

| 策略名 | 角色 | 操作 | 规则 |
|--------|------|------|------|
| `participations_select_own` | `authenticated` | SELECT | `user_id = auth.uid()` 仅查自己 |
| `participations_insert_own` | `authenticated` | INSERT | `user_id = auth.uid()` 仅为自己报名 |
| `participations_all_admin` | `service_role` | ALL | 管理员可查看所有报名 |

## 5. 看板 RPC 函数

三个函数均使用 `SECURITY DEFINER` (以表所有者权限运行,绕过 RLS),因为看板需要读取全量数据。

### 5.1 dashboard_summary()

- 调用: `POST /rest/v1/rpc/dashboard_summary` (无参数)
- 返回: JSON 对象 `{ total_campaigns, active_campaigns, total_participations, today_participations }`
- 逻辑: 四个独立 COUNT 查询

### 5.2 participation_trend(p_campaign_id BIGINT)

- 调用: `POST /rest/v1/rpc/participation_trend` body: `{"p_campaign_id": 1}` 或 `{}` (全部)
- 返回: TABLE(day DATE, count BIGINT)
- 逻辑: 按 `date_trunc('day', participated_at)` 分组计数; 参数为 NULL 时统计全部活动

### 5.3 campaign_participation_counts()

- 调用: `POST /rest/v1/rpc/campaign_participation_counts` (无参数)
- 返回: TABLE(campaign_id BIGINT, name TEXT, status TEXT, participation_count BIGINT, max_participants INTEGER)
- 逻辑: campaigns LEFT JOIN participations 按活动分组计数,按参与人数降序

## 6. 实施步骤

全部通过 nubase MCP 工具完成:

| 步骤 | 工具 | 内容 | 说明 |
|------|------|------|------|
| 1 | `executeSqlDryRun` | 预览建表 + RLS + 函数 SQL | 检查语句数和风险等级,不实际执行 |
| 2 | `executeSql` | 创建 2 张表 + 3 个索引 + updated_at 触发器 | 核心 schema |
| 3 | `executeSql` | 启用 RLS + 创建 5 条策略 | 行级安全 |
| 4 | `executeSql` | 创建 3 个 RPC 函数 | 看板统计 |
| 5 | `executeSql` | 插入种子数据 | 3 个活动 + ~15 条报名记录 |
| 6 | `listTables` | 验证表已创建 | 确认 schema |
| 7 | `executeSql` | 测试 RPC 函数 | SELECT dashboard_summary() 等 |

### 6.1 种子数据

3 个活动:
- "夏季大促" (active, 2026-08-01 ~ 2026-08-31, max 500)
- "新品体验官招募" (active, 2026-08-10 ~ 长期, 不限人数)
- "内测反馈有奖" (closed, 2026-07-01 ~ 2026-07-31, max 100)

~15 条报名记录: 跨 3 个活动,使用 `gen_random_uuid()` 生成模拟用户 ID,参与时间分布在最近 5 天。

## 7. 错误处理

| 场景 | 处理方式 |
|------|---------|
| 表已存在 | 实施前先 `listTables` 检查,确认不存在再创建 |
| 函数已存在 | 使用 `CREATE OR REPLACE FUNCTION`,幂等更新 |
| 重复报名 | `UNIQUE(campaign_id, user_id)` 约束自动拒绝 |
| 活动人数已满 | PostgREST 层在 INSERT 前检查 (应用逻辑) |
| SQL 执行风险 | 步骤 1 的 `executeSqlDryRun` 预先拦截 |

## 8. 验证标准

实施完成后通过以下查询验证:
1. `SELECT * FROM public.campaigns` — 返回 3 条活动
2. `SELECT * FROM public.participations` — 返回 ~15 条报名
3. `SELECT public.dashboard_summary()` — 返回 JSON 统计数据
4. `SELECT * FROM public.participation_trend(NULL)` — 返回按日趋势
5. `SELECT * FROM public.campaign_participation_counts()` — 返回各活动参与明细
