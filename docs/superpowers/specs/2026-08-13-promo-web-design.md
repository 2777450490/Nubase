# 运营推广前端(promo-web)设计文档

> 日期: 2026-08-13
> 方案: React + Vite SPA + nubase Assets 发布 (方案 A)
> 配套后端: 2026-08-13-promo-campaign-system-design.md(活动管理 + 看板 RPC)

## 1. 概述

### 1.1 目标

为已构建的运营推广活动管理系统提供一个前端展示页面,包含:
- 活动列表页(公开浏览活动)
- 数据看板页(统计总览 + 参与趋势 + 各活动参与明细)

通过 nubase Assets CDN 发布为公开静态站点,无需登录即可访问。

### 1.2 方案选择

- 技术栈: React + Vite + TypeScript + Tailwind CSS + react-router-dom + recharts
- 发布: `assetsUpload` 上传构建产物到 `/assets/v1/promo/`,`assetsUpdateSettings` 设置 SPA fallback
- API: 全部使用 **anon key**(公开安全,可入浏览器),看板 RPC 函数为 SECURITY DEFINER + PUBLIC EXECUTE,anon 可调用
- 无认证流程、无报名功能(用户选择范围)

### 1.3 不包含的功能

- 用户注册/登录
- 活动报名
- 活动创建/编辑(运营后台)
- 服务端渲染 / API 路由(纯静态 SPA)

## 2. 架构与数据流

### 2.1 架构

```
┌─────────────────────────────────────────────────────────┐
│   promo-web (React + Vite + Tailwind SPA)               │
│   frontend/apps/promo-web                               │
│                                                         │
│  ┌──────────────┐    ┌─────────────────────────────┐    │
│  │  / 活动列表   │    │  /dashboard 数据看板         │    │
│  │  react-router │    │  统计卡片+趋势图+明细表       │    │
│  └──────┬───────┘    └──────────────┬──────────────┘    │
│         │                          │                    │
│         ▼                          ▼                    │
│  ┌─────────────────────────────────────────────┐        │
│  │  src/lib/api.ts — 统一 API client           │        │
│  │  baseUrl: '' (相对路径,同源)                 │        │
│  │  header: apikey: <anon key>                │        │
│  └──────────────────┬──────────────────────────┘        │
└─────────────────────┼────────────────────────────────────┘
                      │ 同源调用 (部署在 :9999/assets/v1,
                      │ API 在 :9999/rest/v1 → 无 CORS 问题)
┌─────────────────────▼────────────────────────────────────┐
│              Nubase Backend (:9999)                      │
│  /rest/v1/campaigns             — 活动列表                │
│  /rest/v1/rpc/dashboard_summary — 看板总览                │
│  /rest/v1/rpc/participation_trend — 参与趋势              │
│  /rest/v1/rpc/campaign_participation_counts — 活动明细     │
└──────────────────────────────────────────────────────────┘
```

### 2.2 API 调用清单(全部 anon key)

| 功能 | 调用 | 响应 |
|------|------|------|
| 活动列表 | `GET /rest/v1/campaigns?select=id,name,description,status,start_time,end_time,max_participants&order=start_time.desc` | 数组 |
| 看板总览 | `POST /rest/v1/rpc/dashboard_summary` body `{}` | `[{"result":{...}}]` |
| 参与趋势 | `POST /rest/v1/rpc/participation_trend` body `{}` | `[{"day":"...","count":n}]` |
| 活动明细 | `POST /rest/v1/rpc/campaign_participation_counts` body `{}` | 数组 |

### 2.3 认证模型

- 前端只携带 anon key(`apikey` 头),不包含任何 service_role 密钥
- 看板 RPC 函数 SECURITY DEFINER 且 PUBLIC EXECUTE,anon 可调用
- 前端部署在 Assets CDN,公开只读,无需用户认证

## 3. 项目结构

### 3.1 目录

```
frontend/apps/promo-web/
├── package.json              # 独立包
├── vite.config.ts            # Vite 配置,base: './'
├── tailwind.config.js
├── postcss.config.js
├── index.html
├── tsconfig.json
└── src/
    ├── main.tsx              # React 入口 + Router
    ├── App.tsx               # 布局(顶部导航) + 路由定义
    ├── lib/
    │   └── api.ts            # 统一 API client
    ├── types.ts              # 类型定义
    ├── components/
    │   ├── CampaignCard.tsx  # 活动卡片
    │   ├── StatusBadge.tsx   # 状态徽章
    │   └── StatCard.tsx      # 统计卡片
    └── pages/
        ├── CampaignListPage.tsx
        └── DashboardPage.tsx
```

### 3.2 技术决策

| 决策点 | 方案 | 原因 |
|--------|------|------|
| 路由 | `react-router-dom` v6 | 两个页面 + URL 直达 |
| 图表 | `recharts` 柱状图 | 轻量、React 原生 |
| Vite `base` | `'./'` | 相对路径,Assets 子路径部署可移植 |
| API base | `''`(相对路径) | 与 Assets 同源(:9999),无 CORS |
| anon key | `VITE_NUBASE_ANON_KEY` 环境变量注入 | 不硬编码源码;anon key 公开安全 |

### 3.3 API client(`src/lib/api.ts`)

```typescript
const ANON_KEY = import.meta.env.VITE_NUBASE_ANON_KEY;

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: {
      apikey: ANON_KEY,
      Authorization: `Bearer ${ANON_KEY}`,
      'Content-Type': 'application/json',
      ...init?.headers,
    },
  });
  if (!res.ok) throw new Error(`API ${res.status}: ${await res.text()}`);
  return res.json();
}

export const api = {
  listCampaigns: () =>
    request('/rest/v1/campaigns?select=id,name,description,status,start_time,end_time,max_participants&order=start_time.desc'),
  dashboardSummary: () =>
    request<[{ result: DashboardSummary }]>('/rest/v1/rpc/dashboard_summary', { method: 'POST', body: '{}' }),
  participationTrend: () =>
    request<TrendPoint[]>('/rest/v1/rpc/participation_trend', { method: 'POST', body: '{}' }),
  campaignCounts: () =>
    request<CampaignCount[]>('/rest/v1/rpc/campaign_participation_counts', { method: 'POST', body: '{}' }),
};
```

## 4. 页面设计

### 4.1 活动列表页 (`/`)

- 顶部导航: 活动列表 / 数据看板 Tab
- 状态筛选: 全部 / 进行中 / 已结束
- 活动卡片: 名称、状态徽章(active=绿 / closed=灰 / draft=蓝)、时间范围、人数进度条
- 空态/错误态处理

### 4.2 数据看板页 (`/dashboard`)

- 4 个统计卡片: 活动总数、进行中、总参与数、今日参与数
- 参与趋势柱状图(recharts,按天)
- 各活动参与明细表: 活动名、状态、参与数、人数上限、进度百分比
- 加载中/错误态处理

## 5. 发布流程

### 5.1 构建

```bash
VITE_NUBASE_ANON_KEY=<anon key> pnpm build
```

产物: `dist/index.html` + `dist/assets/*.js` + `dist/assets/*.css`

### 5.2 上传 (nubase MCP 工具)

| dist 文件 | Assets 路径 | 方式 |
|-----------|------------|------|
| `dist/index.html` | `promo/index.html` | `assetsUpload` content |
| `dist/assets/index-*.js` | `promo/assets/index-*.js` | `assetsUpload` content |
| `dist/assets/index-*.css` | `promo/assets/index-*.css` | `assetsUpload` content |
| `dist/vite.svg` | `promo/vite.svg` | `assetsUpload` contentBase64 |

参数: `upsert: true`,文本文件用 `content`,二进制用 `contentBase64`。

### 5.3 SPA fallback 配置

```json
assetsUpdateSettings: { "spaFallbackPath": "promo/index.html" }
```

作用: 直接访问 `/assets/v1/promo/dashboard` 时回退到 index.html,由前端路由接管。

### 5.4 最终访问地址

- 首页: `http://localhost:9999/assets/v1/promo/`
- 看板: `http://localhost:9999/assets/v1/promo/dashboard`

## 6. 错误处理

| 场景 | 处理 |
|------|------|
| API 请求失败 | 页面显示错误提示 + 重试按钮 |
| anon key 未配置 | 构建时提示 `VITE_NUBASE_ANON_KEY` 缺失 |
| 上传文件重名 | `upsert: true` 覆盖 |
| spaFallback 已存在 | `assetsUpdateSettings` 幂等更新 |
| API 返回空数据 | 页面显示空态提示 |

## 7. 验证标准

1. 本地 `pnpm dev`: 两个页面正常渲染、API 数据正确
2. 发布后 `http://localhost:9999/assets/v1/promo/` HTTP 200 且页面正常
3. 深度链接 `http://localhost:9999/assets/v1/promo/dashboard` 可直达(spaFallback 生效)
4. 数据正确: 活动列表 3 个活动,看板统计 3/2/15/2,趋势 6 天,明细 8/5/2
