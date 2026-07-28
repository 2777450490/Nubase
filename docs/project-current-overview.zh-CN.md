# Nubase 项目现状说明

> 文档基线：2026-07-14，`main` 分支，提交 `ab9d515`。
>
> 本文以当前仓库代码、配置、Controller、前端路由和部署脚本为事实源。工作区中的 `src/main/resources/application.yml` 存在未提交的本地运行参数覆盖，本文不会展示其中的密码、Token 或加密主密钥。

## 1. 项目定位

Nubase 是一个面向 AI 原生应用和 AI Coding 工作流的自托管后端与部署平台。它将传统 BaaS 能力、AI 基础设施和 Agent 操作入口整合到同一套服务中。

当前项目的主要组成是：

- Java/Spring Boot 单体后端，提供控制面和数据面 API。
- PostgreSQL 多项目数据库架构，每个项目使用独立的物理数据库。
- Next.js Studio 管理控制台。
- Next.js 官网和文档站。
- HTTP MCP Server 与本地 `nubase_cli` stdio MCP bridge。
- Edge Functions、静态 Assets、定时任务和 Cloudflare App Worker 部署能力。
- JAR、Docker、all-in-one Docker 和 systemd 等运行方式。

当前已落地的八个核心产品模块是：

1. Database
2. Auth
3. Storage
4. Assets
5. Functions
6. AI Gateway
7. Memory
8. cron

此外还包含 Studio、Deployment、App Worker、Agent Metadata 和 MCP 工具链。

## 2. 技术栈

| 层次 | 当前技术 |
| --- | --- |
| 后端语言 | Java 17 |
| 后端框架 | Spring Boot 3.2.0、Spring MVC、Spring Security |
| 数据访问 | Spring Data JPA、JDBC、Hibernate、HikariCP、Druid |
| 数据库 | PostgreSQL 15、pgvector、Flyway |
| 缓存 | Caffeine，或 Redis/Lettuce |
| AI Provider | OpenAI、Anthropic、OpenAI-compatible Provider |
| 对象存储 | Cloudflare R2 / S3-compatible |
| 向量存储 | pgvector；可选 AWS S3 Vectors |
| 前端框架 | Next.js 14.2、React 18、TypeScript、Tailwind CSS |
| 前端工程 | pnpm workspace、Node.js 22.16.0、pnpm 10.11.0 |
| MCP | Spring AI MCP HTTP Server、`nubase_cli` stdio bridge |
| 部署运行时 | JAR、Docker、all-in-one Docker、systemd |
| Cloud Runtime | Cloudflare Workers for Platforms |

主要构建入口：

- 后端：`pom.xml`
- 前端：`frontend/package.json`
- Backend-only 镜像：`Dockerfile`
- 一体化镜像：`Dockerfile.all-in-one`

## 3. 项目总体结构

### 3.1 顶层目录

```text
Nubase/
├── src/
│   ├── main/java/ai/nubase/       # Java backend
│   ├── main/resources/            # YAML, Flyway, tenant schema SQL, prompts
│   └── test/                      # Unit and integration tests
├── frontend/
│   ├── apps/studio/               # Management console, port 3000
│   ├── apps/www/                  # Marketing and docs website, port 3001
│   └── packages/
│       ├── ui/                    # Shared UI components
│       ├── config/                # Shared frontend configuration
│       └── mcp-bridge/            # Public npm package nubase_cli
├── cloudflare/functions-dispatcher/
│                                      # Cloudflare function dispatcher
├── docker/all-in-one/              # All-in-one container entrypoint
├── script/deploy/                  # systemd and production startup scripts
├── docs/                           # Architecture and usage documentation
├── .github/workflows/              # Docker, npm and secret-scan workflows
├── pg-docker-compose.yml           # Local PostgreSQL 15 and pgvector
├── Dockerfile                      # Backend-only image
└── Dockerfile.all-in-one           # Backend, Studio, PostgreSQL and Redis
```

### 3.2 后端包结构

后端主入口是：

```text
src/main/java/ai/nubase/NuBaseApplication.java
```

主要包职责如下：

| 包 | 职责 |
| --- | --- |
| `agent` | Agent instructions、capabilities、connect config |
| `ai.gateway` | OpenAI/Anthropic 网关、上游路由、网关密钥、计费统计 |
| `assets` | 生成式前端静态文件和 CDN 发布 |
| `auth` | 项目用户认证、OAuth、MFA、SSO、Storage API |
| `common` | 安全、CORS、租户上下文、多数据源、公共工具 |
| `cron` | 定时任务、调度执行、运行历史 |
| `deploy` | 应用部署记录、回滚、Cloudflare App Worker |
| `functions` | Edge Function 定义、版本、密钥、调用网关 |
| `mcp` | Spring AI MCP 工具注册 |
| `mem` | Memory 写入、推理、检索、实体和历史 |
| `metadata` | 控制面 JPA 实体和 Repository |
| `platform` | 平台设置、动态 SMTP、平台事件 |
| `postgrest` | Java 版 PostgREST、SQL 生成、Schema Cache、多库路由 |

应用入口排除了默认 JPA Repository、Redis Repository 和默认 Security 自动配置，改为自行管理：

- Metadata JPA Repository。
- 多租户 JPA/JDBC 路由。
- Redis 使用方式。
- Spring Security Filter Chain。
- 事务和定时调度。
- 配置属性扫描。

### 3.3 前端 Monorepo

前端是 pnpm workspace：

```text
frontend/
├── apps/
│   ├── studio/
│   └── www/
└── packages/
    ├── config/
    ├── mcp-bridge/
    └── ui/
```

各工程职责：

- `apps/studio`：项目和平台管理控制台。
- `apps/www`：官网、产品介绍、文档、Blog、News 和比较页面。
- `packages/ui`：共享组件。
- `packages/config`：共享 Tailwind、TypeScript 等配置。
- `packages/mcp-bridge`：发布为 npm 包 `nubase_cli`，为 Codex、Claude Code、Cursor、IDEA 等客户端提供本地 MCP bridge。

### 3.4 数据库架构

Nubase 使用两层数据库模型：

```text
Metadata Database
  ├── platform users and settings
  ├── project configs and ownership
  ├── encrypted project credentials
  ├── SQL history and snippets
  ├── edge function metadata
  ├── scheduled jobs
  └── deployment records

Project Database A
  ├── public.*
  ├── auth.*
  ├── storage.*
  ├── assets.*
  ├── mem.*
  └── ai_gateway.*

Project Database B
  └── same isolated schemas
```

#### Metadata Database

Metadata Database 是控制面数据库，主要保存：

- 项目数据库连接配置。
- 加密后的数据库密码和 JWT Secret。
- 平台用户和外部身份。
- 平台用户与项目的归属关系。
- 平台动态设置。
- SQL 片段和执行历史。
- Edge Function 元数据、版本、Secret 和调用日志。
- 定时任务及运行历史。
- 应用部署记录和部署步骤。

主要表包括：

- `database_configs`
- `platform_users`
- `platform_external_identities`
- `platform_user_projects`
- `platform_settings`
- `sql_snippets`
- `sql_execution_records`
- `edge_functions`
- `edge_function_versions`
- `edge_function_secrets`
- `edge_function_invocations`
- `scheduled_jobs`
- `scheduled_job_runs`
- `app_deployments`
- `app_deployment_steps`

Metadata Database 的 Flyway 迁移位于：

```text
src/main/resources/db/migration
```

#### Project Database

每个项目使用一个独立的物理 PostgreSQL 数据库，主要 schema 和表如下。

Auth：

- `auth.users`
- `auth.sessions`
- `auth.refresh_tokens`
- `auth.identities`
- `auth.one_time_tokens`
- `auth.mfa_factors`
- `auth.mfa_challenges`
- `auth.sso_providers`
- `auth.saml_providers`

Storage：

- `storage.buckets`
- `storage.objects`
- `storage.buckets_vectors`
- `storage.vector_indexes`

Assets：

- `assets.files`
- `assets.settings`

Memory：

- `mem.memories`
- `mem.memory_history`
- `mem.entities`
- `mem.session_messages`
- `mem.config`

AI Gateway：

- `ai_gateway.upstream_configs`
- `ai_gateway.api_keys`
- `ai_gateway.api_usage_logs`
- `ai_gateway.daily_token_usage`
- `ai_gateway.model_pricing`

项目数据库初始化 SQL 位于：

```text
src/main/resources/db/supabase
```

## 4. 项目启动方式

### 4.1 本地前后端分离启动

环境要求：

- Java 17
- Maven 3.9+
- Docker
- Node.js
- pnpm

启动本地 PostgreSQL 15 和 pgvector：

```bash
docker compose -f pg-docker-compose.yml up -d
```

创建本地配置覆盖：

```bash
cp src/main/resources/application-dev.yml.example \
   src/main/resources/application-dev.yml
```

配置启动所需的主密钥和 Metadata 管理 Key：

```bash
export PGRST_ENCRYPTION_MASTER_KEY="$(openssl rand -base64 32)"
export METADATA_SERVICE_ROLE_KEY="replace-with-a-long-random-admin-token"
```

如需启用 LLM 驱动的 Memory：

```bash
export OPENAI_API_KEY="replace-with-provider-key"
```

启动后端：

```bash
mvn spring-boot:run
```

启动 Studio：

```bash
cd frontend
pnpm install
NEXT_PUBLIC_NUBASE_API_URL="http://localhost:9999" pnpm dev:studio
```

启动官网：

```bash
cd frontend
pnpm dev:www
```

### 4.2 构建带 Studio 的 JAR

使用 Maven `with-frontend` Profile：

```bash
mvn -Pwith-frontend clean package
java -jar target/nubase-1.0.0-SNAPSHOT.jar
```

该 Profile 会：

1. 安装指定版本的 Node.js 和 pnpm。
2. 安装 frontend workspace 依赖。
3. 将 Studio 构建为静态导出。
4. 设置 `basePath=/studio`。
5. 把导出文件复制到 JAR 的 `static/studio`。
6. 由 Java 在 `9999` 端口同时提供 API 和 Studio。

普通 `mvn package` 或 `mvn spring-boot:run` 不会自动打包 Studio。

### 4.3 Backend-only Docker

构建：

```bash
docker build -t nubase-backend:local .
```

该镜像只包含 Java 后端，不包含 Studio、PostgreSQL 和 Redis。

镜像暴露端口：

```text
9999
```

### 4.4 all-in-one Docker

构建：

```bash
docker build -f Dockerfile.all-in-one -t nubase:local .
```

启动：

```bash
docker run -d \
  --name nubase \
  -p 9999:9999 \
  -p 5432:5432 \
  -v nubase_data:/data \
  nubase:local
```

容器内部运行：

- Java Backend 和 Studio：`9999`
- PostgreSQL 15 和 pgvector：`5432`
- Redis：容器内部 `6379`

首次启动会在 `/data/secrets` 生成：

- PostgreSQL 租户凭据加密主密钥。
- Metadata service-role key。

必须持久化并复用同一个 `/data` Volume。丢失主密钥后，已有加密项目凭据将无法解密。

### 4.5 systemd 生产启动

生产脚本使用以下目录：

| 项目 | 路径或值 |
| --- | --- |
| 工作目录 | `/root/nubase` |
| 当前 JAR | `/root/nubase/nubase.jar` |
| JAR 暂存目录 | `/root/nubase/jar` |
| 环境文件 | `/root/nubase/nubase.env` |
| 日志文件 | `/root/nubase/logs/java.log` |
| Heap Dump 目录 | `/root/nubase/jvm` |
| JVM Heap | `-Xms1G -Xmx4G` |
| 默认端口 | `9999` |

启动脚本会：

1. 将暂存 JAR 移动到工作目录。
2. 向旧 Java 进程发送 SIGTERM。
3. 等待最多 60 秒。
4. 超时后发送 SIGKILL。
5. 加载 `/root/nubase/nubase.env`。
6. 使用 `exec java` 启动新进程。
7. 在 OOM 时生成 Heap Dump。

systemd 服务配置：

```text
script/deploy/nubase.service
```

生产环境变量模板：

```text
script/deploy/nubase.env.example
```

## 5. 后端启动参数

### 5.1 Spring Boot 和数据库核心参数

| 参数 | 默认值或要求 | 用途 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` | Spring Profile |
| `SERVER_PORT` | `9999` | 标准 Spring Boot 服务端口覆盖 |
| `NUBASE_SERVER_PORT` | `9999` | 生产启动脚本使用，映射到 `-Dserver.port` |
| `METADATA_DB_URL` | 仓库基线指向本地 PostgreSQL | Metadata Database JDBC URL |
| `METADATA_DB_USER` | `postgres` | Metadata Database 用户 |
| `METADATA_DB_PASSWORD` | 本地开发默认值 | Metadata Database 密码 |
| `PGRST_ENCRYPTION_MASTER_KEY` | 正式部署必需 | 加密项目 DB 密码和 JWT Secret |
| `PGRST_ENCRYPTION_MASTER_KEY_FILE` | 可选 | 从文件读取主密钥 |
| `METADATA_SERVICE_ROLE_KEY` | 正式部署必需 | Metadata 管理接口认证 |
| `POSTGRES_HOST` | `localhost` | 创建项目数据库时连接的 PostgreSQL 主机 |
| `POSTGRES_PORT` | `5432` | 项目数据库创建端口 |
| `FLYWAY_ENABLED` | `true` | Metadata Flyway 迁移 |
| `PGRST_MAX_TOTAL_CONNECTIONS` | `500` | 所有动态项目连接池总上限 |
| `PGRST_DEFAULT_POOL_SIZE` | `10` | 每个项目默认连接池大小 |

加密主密钥的运行时优先级：

1. `PGRST_ENCRYPTION_MASTER_KEY`
2. `PGRST_ENCRYPTION_MASTER_KEY_FILE`
3. 配置文件直接值

`PGRST_ENCRYPTION_MASTER_KEY` 必须是 Base64 编码的 32 字节密钥。已有数据运行期间必须保持稳定，不能在每次重启时重新生成。

### 5.2 Metadata DataSource 默认设置

默认连接池参数：

| 配置 | 默认值 |
| --- | --- |
| `maximum-pool-size` | `10` |
| `minimum-idle` | `1` |
| `connection-timeout` | `30000 ms` |
| `idle-timeout` | `600000 ms` |
| `max-lifetime` | `1800000 ms` |

JPA 设置：

- PostgreSQL Dialect。
- `ddl-auto=none`。
- `open-in-view=false`。
- JDBC 时区为 UTC。
- Hibernate 不在 EntityManagerFactory 初始化期间读取 JDBC Metadata。

### 5.3 Redis 和缓存

| 参数 | 默认值 | 用途 |
| --- | --- | --- |
| `NUBASE_CACHE_TYPE` | `caffeine` | `caffeine` 或 `redis` |
| `REDIS_HOST` | `localhost` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `REDIS_DATABASE` | `0` | Redis Database |
| `REDIS_SSL` | `false` | Redis TLS |

Caffeine 是单实例默认方案。多节点部署需要 Redis 共享缓存、限流和部分状态。

### 5.4 SMTP 和邮件

| 参数 | 默认值 |
| --- | --- |
| `SMTP_HOST` | `smtp.example.com` |
| `SMTP_PORT` | `587` |
| `SMTP_USERNAME` | 空 |
| `SMTP_PASSWORD` | 空 |
| `MAIL_FROM_ADDRESS` | `noreply@example.com` |
| `MAIL_FROM_NAME` | `Nubase Auth` |

SMTP 用于：

- 项目用户注册确认。
- 密码恢复。
- Magic Link 和 OTP。
- Studio 平台账号邮箱验证。

### 5.5 Platform Auth 和 OAuth

主要参数：

- `NUBASE_PLATFORM_APP_NAME`
- `NUBASE_PLATFORM_EMAIL_VERIFICATION_ENABLED`
- `NUBASE_PLATFORM_OTP_LENGTH`
- `NUBASE_PLATFORM_OTP_EXPIRATION_SECONDS`
- `NUBASE_PLATFORM_OAUTH_SITE_URL`
- `NUBASE_PLATFORM_OAUTH_GOOGLE_CLIENT_ID`
- `NUBASE_PLATFORM_OAUTH_GOOGLE_CLIENT_SECRET`
- `NUBASE_PLATFORM_OAUTH_GITHUB_CLIENT_ID`
- `NUBASE_PLATFORM_OAUTH_GITHUB_CLIENT_SECRET`
- `NUBASE_AUTH_APP_DOMAIN`
- `NUBASE_AUTH_APP_SCHEME`

代码还支持以下 Spring 属性：

- `nubase.platform.jwt-secret`
- `nubase.platform.jwt-expiration-seconds`
- `nubase.platform.signup-enabled`

普通 JAR 默认启用 Studio 邮箱验证。all-in-one 镜像没有内置 SMTP，因此默认将 `NUBASE_PLATFORM_EMAIL_VERIFICATION_ENABLED` 设置为 `false`。

### 5.6 Storage 和 Assets

Storage/R2 参数：

- `R2_ACCOUNT_ID`
- `R2_ACCESS_KEY_ID`
- `R2_SECRET_ACCESS_KEY`
- `R2_ENDPOINT`
- `R2_PUBLIC_URL`
- `R2_GLOBAL_BUCKET`

Assets 参数：

| 参数 | 默认值 |
| --- | --- |
| `NUBASE_ASSETS_ENABLED` | `true` |
| `NUBASE_ASSETS_BUCKET` | 空 |
| `NUBASE_ASSETS_PUBLIC_BASE_URL` | 空 |
| `NUBASE_ASSETS_MAX_FILE_SIZE` | `26214400` |

Assets 有两种交付模式：

1. Backend mode：`NUBASE_ASSETS_BUCKET` 为空，文件位于全局 Storage bucket 的保留前缀，由后端 `/assets/v1/**` 输出。
2. CDN mode：配置专用公开 bucket 和 public base URL，文件直接通过 CDN 域名访问。

### 5.7 S3 Vectors

主要参数：

- `NUBASE_STORAGE_S3VECTORS_ENABLED`
- `S3VECTORS_REGION`
- `S3VECTORS_ACCESS_KEY_ID`
- `S3VECTORS_SECRET_ACCESS_KEY`
- `S3VECTORS_ENDPOINT`
- `S3VECTORS_BUCKET_NAME`

`NUBASE_STORAGE_S3VECTORS_ENABLED` 默认是 `false`。仅配置 region、credential 和 bucket 不会自动启用 `/storage/v1/vector/**` Controller。

### 5.8 Edge Functions

| 参数 | 默认值 |
| --- | --- |
| `NUBASE_FUNCTIONS_ENABLED` | `true` |
| `NUBASE_FUNCTIONS_EXECUTOR_PROVIDER` | `local` |
| `NUBASE_FUNCTIONS_EXECUTOR_TIMEOUT_MS` | `30000` |
| `NUBASE_FUNCTIONS_MAX_REQUEST_BYTES` | `10485760` |
| `NUBASE_FUNCTIONS_MAX_RESPONSE_BYTES` | `10485760` |
| `NUBASE_FUNCTIONS_PER_PROJECT_RPM` | `600` |
| `NUBASE_FUNCTIONS_PER_FUNCTION_RPM` | `120` |
| `NUBASE_FUNCTIONS_LOCAL_BASE_URL` | `http://localhost:8787` |
| `NUBASE_FUNCTIONS_INVOCATION_LOG_RETENTION_DAYS` | `30` |

Cloudflare Executor 还需要：

- Cloudflare API base URL。
- Account ID。
- API Token。
- Dispatch namespace。
- Dispatcher URL。
- Dispatcher Secret。

### 5.9 App Worker

| 参数 | 默认值 |
| --- | --- |
| `NUBASE_MULTIPART_MAX_FILE_SIZE` | `64MB` |
| `NUBASE_MULTIPART_MAX_REQUEST_SIZE` | `128MB` |
| `NUBASE_APP_WORKER_MAX_FILE_SIZE` | `64MB` |
| `NUBASE_APP_WORKER_MAX_REQUEST_SIZE` | `128MB` |
| `NUBASE_APP_WORKER_CLOUDFLARE_ENABLED` | `false` |
| `NUBASE_APP_WORKER_CLOUDFLARE_TIMEOUT_MS` | `30000` |

Cloudflare App Worker 还需要 Account ID、API Token，以及 preview/production 两个 dispatch namespace。

### 5.10 cron

| 参数 | 默认值 |
| --- | --- |
| `NUBASE_CRON_ENABLED` | `true` |
| `NUBASE_CRON_TICK_MS` | `30000` |
| `NUBASE_CRON_MAX_JOBS_PER_TICK` | `50` |
| `NUBASE_CRON_MAX_CONCURRENT_JOBS` | `8` |
| `NUBASE_CRON_EXECUTION_QUEUE_CAPACITY` | `100` |
| `NUBASE_CRON_DEFAULT_TIMEOUT_SECONDS` | `60` |
| `NUBASE_CRON_MAX_TIMEOUT_SECONDS` | `600` |
| `NUBASE_CRON_RUN_RETENTION_DAYS` | `30` |

启动时会验证：

```text
maxJobsPerTick <= maxConcurrentJobs + executionQueueCapacity
defaultTimeoutSeconds >= 1
maxTimeoutSeconds >= defaultTimeoutSeconds
```

配置不满足约束时应用会拒绝启动，避免调度器持续产生队列拒绝记录。

### 5.11 Memory 和 LLM

Memory 默认参数：

| 参数 | 默认值 |
| --- | --- |
| Enabled | `true` |
| Chat Provider | `openai` |
| Embedding Provider | `openai` |
| Chat Model | `gpt-4o-mini` |
| Chat Temperature | `0.0` |
| Embedding Model | `text-embedding-3-small` |
| Embedding Dimensions | `1536` |
| Search TopK | `5` |
| Cosine Distance Threshold | `0.7` |
| FTS Config | `simple` |
| Entity Boost | `true` |
| History | `true` |
| Session Window | `10` |

Provider 参数：

- `OPENAI_API_KEY`
- `OPENAI_BASE_URL`
- `ANTHROPIC_API_KEY`
- `ANTHROPIC_BASE_URL`
- `GENERIC_LLM_API_KEY`
- `GENERIC_LLM_BASE_URL`

Generic Provider 可用于 DashScope、DeepSeek、Moonshot、vLLM、Ollama 等 OpenAI-compatible Endpoint。

配置约束：

- 修改 embedding dimensions 后，必须同步修改项目库 `mem.memories.embedding vector(N)`。
- 修改 FTS config 后，必须重建 `mem.memories` 对应 GIN Index。
- `entity-boost-enabled=true` 会为每次搜索增加一次实体抽取 LLM 调用。
- Session 注入事实抽取默认关闭，避免增加 Token 成本或混合无关会话。

## 6. 前端启动参数

### 6.1 Studio

| 参数 | 用途 |
| --- | --- |
| `NEXT_PUBLIC_NUBASE_API_URL` | 浏览器调用的后端公开地址；空值表示相对路径 |
| `NUBASE_INTERNAL_API_URL` | Next standalone server 内部代理目标，默认 `http://127.0.0.1:9999` |
| `STUDIO_STATIC_EXPORT` | Maven 打包时设为 `true`，生成 `/studio` 静态导出 |

Studio 脚本：

```bash
pnpm dev:studio
pnpm --filter @nubase/studio build
pnpm --filter @nubase/studio start
pnpm --filter @nubase/studio typecheck
pnpm --filter @nubase/studio test
```

### 6.2 官网

官网使用：

```text
NEXT_PUBLIC_SITE_URL
```

默认值：

```text
https://nubase.ai
```

官网脚本：

```bash
pnpm dev:www
pnpm --filter @nubase/www build
pnpm --filter @nubase/www start
pnpm --filter @nubase/www typecheck
```

### 6.3 nubase_cli

主要运行参数：

- `NUBASE_URL`
- `NUBASE_PROJECT_KEY`
- `NUBASE_CONFIG`
- `NUBASE_USER_JWT`
- `NUBASE_USER_ID`
- `NUBASE_AGENT_ID`
- `NUBASE_RUN_ID`
- `NUBASE_ALLOW_SQL_EXECUTE`
- `NUBASE_ALLOW_DANGEROUS_SQL`
- `NUBASE_ALLOW_ADMIN_WRITE`
- `NUBASE_RECORD_MIGRATIONS`

权限语义：

- 只读工具默认可用。
- SQL 执行由 `NUBASE_ALLOW_SQL_EXECUTE` 控制。
- 高风险 SQL 由 `NUBASE_ALLOW_DANGEROUS_SQL` 控制。
- Auth、Storage、Gateway、Assets、Functions、cron 等写操作由 `NUBASE_ALLOW_ADMIN_WRITE` 控制。

## 7. 启动后的访问方式

### 7.1 访问矩阵

| 启动方式 | Studio | Backend API | 官网 |
| --- | --- | --- | --- |
| 本地分离开发 | `http://localhost:3000` | `http://localhost:9999` | `http://localhost:3001` |
| 带前端 JAR | `http://localhost:9999/studio/` | `http://localhost:9999` | 不包含 |
| all-in-one Docker | `http://localhost:9999/studio/` | `http://localhost:9999` | 不包含 |
| 远程一体化部署 | `http(s)://host/studio/` | `http(s)://host` | 单独部署 |

后端根路径：

```text
/
```

会重定向到：

```text
/studio/projects/
```

前提是 JAR 使用 `with-frontend` Profile 打包了 Studio。纯后端 JAR 不包含 `static/studio`，访问 `/studio/**` 会返回 404。

### 7.2 Studio 静态路由

Studio 静态导出使用：

```text
basePath=/studio
```

项目 Ref、Memory ID、Storage Bucket 等动态参数无法在构建期枚举。当前实现会生成 `__shell__` 静态页面，并由 Java `StudioWebConfig` 将运行时路由重写到对应 Shell 页面。

### 7.3 API 基础地址

| 服务 | 地址 |
| --- | --- |
| Backend Health | `/auth/v1/health` |
| Gateway Health | `/v1/health` |
| Storage Health | `/storage/v1/health` |
| PostgREST | `/rest/v1/{table}` |
| Auth | `/auth/v1` |
| Storage | `/storage/v1` |
| Assets Public | `/assets/v1` |
| Assets Admin | `/assets/admin/v1` |
| Functions Public | `/functions/v1` |
| Functions Admin | `/functions/admin/v1` |
| Memory | `/mem/v1` |
| AI Gateway Data Plane | `/v1` |
| AI Gateway Control Plane | `/ai-gateway/admin/v1` |
| cron | `/cron/admin/v1` |
| Deployment Records | `/deployments/admin/v1` |
| App Worker | `/deployments/platform/v1/app-workers` |
| MCP HTTP | `/mcp` |
| Agent Metadata | `/agent/v1` |

### 7.4 认证模型

Nubase 有四类认证上下文。

#### 项目 API Key

```http
apikey: <project-jwt>
```

项目 JWT 包含：

- `ref`：项目标识。
- `role`：`anon`、`authenticated` 或 `service_role`。

后端根据该 Key：

1. 解析项目 Ref 和角色。
2. 从 Metadata Database 加载项目配置。
3. 解密数据库密码和项目 JWT Secret。
4. 创建或复用项目 Hikari DataSource。
5. 设置多租户上下文。
6. 校验项目 API Key 签名。

#### 项目终端用户

```http
Authorization: Bearer <user-jwt>
```

User JWT 用于：

- `auth.uid()`。
- PostgreSQL RLS。
- Memory Owner 限制。
- 项目用户级 API 行为。

#### AI Gateway Key

```http
Authorization: Bearer nbk_<project>_<secret>
```

Gateway Key 由独立 Filter 校验，不走普通项目 JWT 数据面认证链。

#### Studio Platform JWT

Studio 平台用户登录后获得独立 Platform JWT，用于：

- 项目列表和创建。
- 项目 Provision。
- 平台用户管理。
- 平台 SMTP/R2 设置。
- Cloudflare App Worker 控制面。

### 7.5 Public Assets 和子域名

`/assets/v1/**` 可以不携带 `apikey`，但后端必须从项目子域名识别项目：

```text
https://<project-ref>.<domain>/assets/v1/index.html
```

本地开发可通过 `Host` Header 模拟：

```bash
curl -H "Host: project-ref.localhost" \
  http://localhost:9999/assets/v1/index.html
```

## 8. 项目功能

### 8.1 Database 和 PostgREST

Java 实现的 PostgREST 风格接口：

- `GET /rest/v1/**`
- `POST /rest/v1/**`
- `PUT /rest/v1/**`
- `PATCH /rest/v1/**`
- `DELETE /rest/v1/**`
- `OPTIONS /rest/v1/**`

主要能力：

- Select 和嵌套 Select。
- Filter operators 和 quantifiers。
- Order、Limit、Offset、Range。
- Insert、Update、Delete。
- Upsert。
- JSONB 转换。
- Schema Cache。
- RLS 和 JWT Claims。
- 数据库级多租户路由。
- 动态 Hikari DataSource。
- Schema Watcher。

Database 控制面还提供：

- 项目创建和 Provision。
- SQL Execute。
- SQL Dry Run 和风险分类。
- Schema DDL 导出。
- RLS Policy 导出。
- SQL Snippet 管理。
- SQL 执行历史。
- 项目成员管理。
- 项目 API Key 查询。

### 8.2 Auth

项目用户 Auth 支持：

- 邮箱和密码注册登录。
- 匿名注册。
- Refresh Token Rotation。
- Password Recovery。
- Magic Link。
- Email/Phone OTP。
- PKCE Code Exchange。
- ID Token 登录。
- 邮箱和手机号验证。
- Logout 和 Session 管理。
- 用户 Metadata 更新。
- Identity Linking/Unlinking。
- MFA/TOTP Enrollment、Challenge 和 Verify。
- OAuth Authorize/Callback。
- Google、GitHub、微信等租户 OAuth 配置。
- SAML SSO Provider、Metadata 和 ACS。
- 用户邀请。
- Admin 用户创建、查询、更新、软删除和硬删除。
- 用户 Session 和 Factor 管理。
- Auth 策略、Redirect Allowlist 和邮件模板管理。

Studio 平台账号系统支持：

- Platform Signup/Login。
- 邮箱 OTP 验证。
- 密码修改。
- Google GIS/One Tap。
- Google/GitHub OAuth Redirect。
- 平台用户和项目归属管理。

### 8.3 Storage

Storage 提供 Supabase 风格接口：

- Bucket 创建、列表、详情、更新、清空和删除。
- Object 上传、下载和删除。
- 目录列表和 `list-v2`。
- Public、Authenticated 和 Signed Object URL。
- Object Info 和 HEAD。
- Copy 和 Move。
- Signed Upload。
- TUS Resumable Upload。
- CDN Invalidation。
- 文件大小和 MIME 限制。
- R2/S3-compatible Object Backend。

`/storage/v1/render/image/**` 当前只复用原始对象下载，不会执行 Width、Height、Format、Quality 等图片转换。

S3 Vectors Controller 支持：

- Vector Bucket Create/Delete/List/Get。
- Index Create/Delete/List/Get。
- Vector Put/Get/List/Query/Delete。

该 Controller 默认关闭，需要显式启用。

### 8.4 Assets

Assets 是面向生成式应用前端的静态发布模块：

- 上传、覆盖、查询和删除 HTML/CSS/JS、图片和字体。
- 公共 `/assets/v1/{path}` 访问。
- ETag、Cache-Control 和 304。
- 项目默认缓存设置。
- SPA Fallback。
- 自定义 Public Base URL。
- Backend Serving 或专用 R2/CDN 两种模式。
- MCP `assets_upload` 返回最终 Public URL。

### 8.5 Edge Functions

Functions 提供：

- 创建和更新函数元数据。
- 启用/禁用函数。
- `verify_jwt` 控制。
- 上传和部署 Source Bundle。
- Version History 和 Active Version。
- 每函数 Secret 管理。
- 调用日志。
- 项目级和函数级 RPM 限流。
- 请求和响应体大小限制。
- Local HTTP Executor。
- Cloudflare Workers for Platforms Executor。
- `ANY /functions/v1/{slug}/**` 公共调用入口。

### 8.6 AI Gateway

OpenAI-compatible 数据面：

- `POST /v1/chat/completions`
- `POST /v1/responses`
- `POST /v1/responses/compact`
- `POST /v1/memories/trace_summarize`
- `GET /v1/models`
- `GET /v1/models/{model}`

Anthropic-compatible 数据面：

- `POST /v1/messages`
- `POST /v1/messages/count_tokens`
- Streaming/SSE。
- Model List/Detail。
- File Upload/Content。
- Event Logging。
- Claude Code 兼容 Catch-all Path。

控制面：

- Upstream CRUD。
- Upstream Cache Refresh。
- `nbk_` Gateway Key 签发、更新和撤销。
- Usage Overview、Daily、By Model 和 Logs。
- Model Pricing CRUD。
- 平台级跨项目 Upstream 和 Usage 汇总。
- Token、Request 和 Cost 统计。

### 8.7 Memory

Memory 写入流程：

1. 接收消息。
2. LLM 提取长期事实。
3. 为每条事实决定 `ADD`、`UPDATE`、`DELETE` 或 `NONE`。
4. 生成 Embedding。
5. 写入 `mem.memories`。
6. 追加 `mem.memory_history`。
7. 抽取和关联实体。

检索流程组合：

- pgvector Cosine Search。
- PostgreSQL Full-text Search。
- Query Entity Extraction。
- Entity Boost。
- Score Fusion。

Memory API 支持：

- 添加 Memory。
- 分页查询。
- 详情查询。
- 更新。
- 单条和批量删除。
- Search。
- History。
- Memory-to-Entity 关系。
- Entity 列表、详情和删除。
- Stats。
- 项目级 Memory Config。
- Service-role Reset。

普通用户访问 Memory 时，Owner 会强制绑定到 Bearer JWT 的 `sub`。没有 Bearer Token 的 `anon` 或 `authenticated` API Key 不能只凭请求体 `userId` 访问 Memory。`service_role` 可以执行跨用户管理操作。

### 8.8 cron

cron 支持两种 Target：

- `edge_function`
- `db_function`

主要能力：

- UTC Crontab Expression。
- 创建、更新、删除和查询任务。
- 单任务 Timeout。
- 执行历史。
- 多实例 CAS Claim。
- 每实例并发线程池和队列。
- 历史自动清理。
- REST 和 MCP 管理。

### 8.9 App Deployment

Deployment 模块用于记录一次完整的应用发布：

- 创建 Deployment。
- 记录每个部署步骤和日志。
- 标记 Deployment 完成或失败。
- 查询状态和步骤。
- 对可逆资源执行 Rollback。

当前 Rollback 主要覆盖：

- Assets。
- cron Jobs。

SQL 和部分不可安全逆转的步骤会被记录为 Skipped，而不是自动执行破坏性逆操作。

### 8.10 App Worker

App Worker 支持：

- 上传 Server Files 和 Asset Files。
- Cloudflare Preview/Production Dispatch Namespace。
- 查询项目 Worker 列表。
- 查询 Worker 详情及远端状态。
- 删除已部署 Worker。
- 项目归属校验。
- 上传文件和请求总大小限制。

### 8.11 Agent Metadata

Agent 元数据接口：

- `GET /agent/v1/instructions`
- `GET /agent/v1/capabilities`
- `GET /agent/v1/connect-config`

支持的客户端标识：

- `codex`
- `claude-code`
- `cursor`
- `idea`
- `generic`

Connect Config 可返回：

- MCP Endpoint。
- 项目 API Key 模板。
- OpenAI Base URL。
- Anthropic Base URL。
- Agent Environment 模板。

### 8.12 MCP

后端 Spring AI MCP 工具包括：

Database：

- 列表和表结构。
- RLS 导出。
- SQL Dry Run。
- SQL Execute。
- 数据库初始化。

Auth：

- 用户查询。
- 用户创建。
- 用户删除。

Storage：

- Bucket 查询。
- Bucket 创建。
- Bucket 删除。

Assets：

- 上传。
- 查询。
- 删除。
- Delivery Settings。

Functions：

- 创建和更新。
- Bundle 部署。
- Secret 管理。
- 调用日志。

Gateway：

- Key 查询。
- Key 签发。
- Key 撤销。

Memory：

- Context。
- Search。
- Write。

cron：

- CRUD。
- Run History。

Deployment/App Worker：

- 状态和日志。
- Rollback。
- Worker 查询和删除。

`frontend/packages/mcp-bridge` 在 HTTP API 之上提供本地 `nubase_cli` stdio MCP，并增加：

- `deploy_app` 一站式编排。
- 本地权限开关。
- SQL 风险分类。
- Migration Audit。
- 发布前 Secret Scan。
- Codex 和 Claude Code Skill 安装。
- 浏览器授权和项目选择。

### 8.13 Studio

Studio 当前包含以下页面和功能：

- 平台登录和注册。
- 邮箱验证。
- 项目列表。
- 新建项目和 Provision。
- 项目 Dashboard。
- Table Editor。
- SQL Editor、SQL History 和 Snippets。
- Auth 用户管理。
- Auth Provider、Settings、Email Templates 和 SSO。
- Storage Bucket/Object 管理。
- Assets 发布和缓存设置。
- Memory Browser、详情、History、Entities 和 Config。
- AI Gateway Upstream、Key、Usage 和 Pricing。
- Edge Functions、Secret、调用测试和日志。
- cron Jobs 和运行历史。
- Connect Agent。
- 项目 API Keys、成员和设置。
- 平台用户管理。
- SMTP 和 R2 平台设置。
- CLI 浏览器授权。

### 8.14 官网

`frontend/apps/www` 提供：

- 首页。
- Features。
- Docs。
- Memory Quickstart。
- Blog。
- News。
- Compare。
- Sitemap。
- Robots。
- `llms.txt`。
- 多语言内容和主题切换。

## 9. 当前已知不一致和缺口

### 9.1 Backend-only Docker Healthcheck 不匹配

`Dockerfile` 使用：

```text
/health
```

作为 Healthcheck，但当前主健康接口是：

```text
/auth/v1/health
```

`/health` 没有实际 Controller，并会进入安全链，因此 Backend-only 镜像可能被持续判定为 Unhealthy。

### 9.2 Studio Standalone Proxy 不完整

Studio standalone 的 Next Proxy 当前只覆盖：

```text
/auth/v1
/rest/v1
/storage/v1
/functions
/assets
/cron
/mem
/v1
```

没有覆盖：

```text
/ai-gateway
/agent
/deployments
/api
```

因此在前后端分离开发时，建议显式配置：

```bash
NEXT_PUBLIC_NUBASE_API_URL="http://localhost:9999"
```

否则 AI Gateway、Connect Agent 和部分 Deployment 页面可能把请求发送到 Studio 自己的 `3000` 端口。一体化 `/studio` 同源模式不受影响。

### 9.3 Studio Logs 路由缺失

Studio 侧边栏包含：

```text
/project/{ref}/logs
```

但当前仓库没有对应的 `page.tsx`，点击后会进入 404。

### 9.4 架构文档部分过期

`docs/architecture.md` 的 Known Gaps 仍将 Edge Functions 列为未实现，但当前代码和 `docs/product-overview.md` 已包含完整的 Functions 模块。

当前功能状态应优先参考：

- 实际 Controller 和 Service。
- `docs/product-overview.md`。
- `README.md` 和 `README.zh-CN.md`。

### 9.5 Swagger 未匿名开放

项目已加入 Springdoc OpenAPI 依赖，但 Security 配置没有匿名放行：

```text
/v3/api-docs
/swagger-ui/**
```

未认证访问会被拒绝。

### 9.6 CORS 生产配置过宽

当前全局 CORS：

- 允许任意 Origin Pattern。
- 允许任意 Method。
- 允许任意 Header。
- 暴露任意 Header。
- 同时允许 Credentials。

适合开发环境，但生产环境应配置明确的 Origin Allowlist。

### 9.7 Storage Image Render 尚未实现图片转换

`/storage/v1/render/image/**` 目前只是返回原始对象内容，不支持真正的：

- Width/Height。
- Resize Mode。
- Format Conversion。
- Quality。
- AVIF/WebP 转换。

### 9.8 S3 Vectors 默认关闭

YAML 中虽然存在 S3 Vectors 的 Region、Credential 和 Bucket 配置，但 Controller 由 `nubase.storage.s3vectors.enabled=true` 条件控制，默认不会注册。

### 9.9 尚未覆盖的产品能力

当前尚未覆盖或未形成完整产品能力的部分主要包括：

- Realtime。
- 自动备份。
- PITR。
- HA 编排。
- 完整的配额和计费系统。
- 企业级 SCIM。
- 托管监控和告警。

## 10. 安全注意事项

正式部署至少需要确认：

- 使用稳定的 32-byte Base64 加密主密钥。
- 不在 Git 中保存主密钥、数据库密码、Provider Key 或 Service-role Key。
- 将真实配置放入环境变量或权限受控的本地配置文件。
- 限制 Metadata 和项目数据库网络访问范围。
- 不向浏览器或 Assets 发布 `service_role` Key。
- 审查 `/auth/v1/admin/**`、`/ai-gateway/**`、`/deployments/**` 和 MCP 的公网暴露方式。
- 对 SQL Execute 和高风险 SQL 保持独立权限开关。
- 多节点部署使用共享 Redis。
- 收紧 CORS Origin。
- 配置反向代理的 TLS、Request Size 和 Timeout。
- 定期轮换 AI Provider、Storage 和 OAuth Credential。

当前工作区的 tracked `application.yml` 包含本地运行参数修改。提交前应确保真实密钥已移出 tracked 文件，并检查：

```bash
git diff -- src/main/resources/application.yml
bash script/check-secrets.sh
```

## 11. 推荐验证方式

### 11.1 后端测试

```bash
mvn test
```

只验证编译：

```bash
mvn -DskipTests compile
```

格式检查和修复：

```bash
mvn spotless:check
mvn spotless:apply
```

### 11.2 前端验证

```bash
cd frontend
pnpm install
pnpm typecheck
pnpm build
pnpm --filter @nubase/studio test
```

### 11.3 MCP Bridge 验证

```bash
cd frontend
pnpm --filter nubase_cli build
pnpm --filter nubase_cli test
```

### 11.4 本地健康检查

```bash
curl http://localhost:9999/auth/v1/health
curl http://localhost:9999/v1/health
curl http://localhost:9999/storage/v1/health
```

### 11.5 PostgreSQL 检查

```bash
docker compose -f pg-docker-compose.yml ps
```

### 11.6 一体化镜像验证

```bash
docker build -f Dockerfile.all-in-one -t nubase:local .
docker run --rm \
  -p 9999:9999 \
  -p 5432:5432 \
  -v nubase_test_data:/data \
  nubase:local
```

启动后检查：

```text
http://localhost:9999/studio/
http://localhost:9999/auth/v1/health
```

## 12. 关键文件索引

| 内容 | 文件 |
| --- | --- |
| Maven 构建 | `pom.xml` |
| 后端入口 | `src/main/java/ai/nubase/NuBaseApplication.java` |
| 主配置 | `src/main/resources/application.yml` |
| 本地配置示例 | `src/main/resources/application-dev.yml.example` |
| Security | `src/main/java/ai/nubase/common/config/SecurityConfig.java` |
| 多租户 Filter | `src/main/java/ai/nubase/common/multitenancy/UnifiedMultiTenancyFilter.java` |
| Studio 静态路由 | `src/main/java/ai/nubase/common/config/StudioWebConfig.java` |
| Metadata Flyway | `src/main/resources/db/migration` |
| 项目数据库初始化 | `src/main/resources/db/supabase` |
| Frontend Workspace | `frontend/package.json` |
| Studio Next 配置 | `frontend/apps/studio/next.config.mjs` |
| Studio API Client | `frontend/apps/studio/src/lib/api.ts` |
| MCP Bridge | `frontend/packages/mcp-bridge` |
| 本地 PostgreSQL | `pg-docker-compose.yml` |
| Backend-only Docker | `Dockerfile` |
| all-in-one Docker | `Dockerfile.all-in-one` |
| all-in-one Entrypoint | `docker/all-in-one/entrypoint.sh` |
| 生产启动脚本 | `script/deploy/nubase_start.sh` |
| systemd Unit | `script/deploy/nubase.service` |
| 生产环境变量示例 | `script/deploy/nubase.env.example` |
| 产品总览 | `docs/product-overview.md` |
| 架构说明 | `docs/architecture.md` |
| 快速开始 | `docs/getting-started.md` |
| MCP 文档 | `docs/mcp.md` |

