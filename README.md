# DeepDataAgent

DeepDataAgent 是一个基于 AgentScope Java、Vue 3 与 TDesign 的 深度数据分析处理以及行动的 Agent 应用。

## 模块结构

- `pom.xml`: 父聚合工程，统一管理版本与插件
- `core/pom.xml`: 二级聚合，组织前后端模块
- `core/core-backend`: Spring Boot 后端，采用 DDD 领域驱动设计
- `core/core-frontend`: Vue 3 + Vite + TDesign Vue Next + TDesign Chat 前端壳
- `deploy/nginx`: 前端 Nginx 反向代理配置
- `data/sqlite`: SQLite 数据文件挂载目录
- `docker`: Docker 部署目录（编排文件 + 各组件 Dockerfile）

## 本地构建

```bash
./mvnw clean package
```

前端开发模式：

```bash
cd core/core-frontend
npm install
npm run dev
```

## 一键部署

配置已内置（后端连接地址位于 `core/core-backend/src/main/resources/application.yaml`），无需复制 `.env`，直接执行（`docker` 目录下提供拆分的多种编排，均需在项目根目录执行）：

| 编排文件                                           | 作用                                   | 命令                                                                          |
| ---------------------------------------------- | ------------------------------------ | --------------------------------------------------------------------------- |
| `docker/docker-compose.yaml`                   | 全量一键启动（推荐）                           | `docker compose -f docker/docker-compose.yaml up --build`                   |
| `docker/docker-compose.infra.yaml`             | 基础建设汇总（PostgreSQL/Redis） | `docker compose -f docker/docker-compose.infra.yaml up --build`             |
| `docker/docker-compose.infra.postgresql.yaml`  | 仅 PostgreSQL                         | `docker compose -f docker/docker-compose.infra.postgresql.yaml up --build`  |
| `docker/docker-compose.infra.redis.yaml`       | 仅 Redis                              | `docker compose -f docker/docker-compose.infra.redis.yaml up -d`            |
| `docker/docker-compose.backend.yaml`           | 仅后端（纯服务，需先启动基础建设）                    | `docker compose -f docker/docker-compose.backend.yaml up --build`           |
| `docker/docker-compose.frontend.yaml`          | 仅前端（纯服务，需先启动后端）                      | `docker compose -f docker/docker-compose.frontend.yaml up --build`          |

所有编排**相互独立、不依赖任何共享网络**：服务间通过宿主机网关 `host.docker.internal` 通信（PostgreSQL/Redis/后端端口均已发布至宿主机，对应 5432/6379/18080）。如需覆盖连接地址（如远程 Docker 主机的 IP），可在启动容器前设置后端环境变量 `SPRING_DATASOURCE_URL`、`REDIS_HOST` 等。拆分运行时按"基础建设 → 后端 → 前端"顺序启动即可。

默认端口：

- 前端：`http://localhost:8080`
- 后端：`http://localhost:18080`
- 健康检查：`http://localhost:18080/actuator/health`

## 核心资源模型

### 四大主原语

| 原语              | 前缀       | 语义要点                                                                                                                                                                                                                                                                                                                                            |
| --------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Agent**       | `agent_` | 版本化全量配置快照（`agent_version`）：`model` 支持字符串简写与 `{id, effort, context_window}` 对象双形态、`tools[]`（toolset/browser/mcp/custom + permission\_policy）、`skills[]`（`{type, skill_id, version}` 绑定）、`mcp_servers[]`、`multiagent`、`agents_md`；更新采用 OCC（version 不匹配 → 409）；「版本激活/回滚」由 `active_version` 指针承载，与 Deployment 无关                                    |
| **Environment** | `env_`   | `config = {type: cloud \| self_hosted, packages(六类包管理器), setup_script ≤64KB, metadata}`；`self_hosted` **仅可登记创建**，Session 装配显式 400「不支持的执行平面」（本系统无 worker 执行平面，见「出界能力登记」）                                                                                                                                                                         |
| **Session**     | `sess_`  | 创建时锁定 Agent 版本快照（嵌入回显）；`environment_id` 必填、`resources[]`（file/github\_repository/memory\_store，含追加挂载与 500MB 配额）、`vault_ids[]`、`environment_variables`；状态机**双字段**——会话级 `status`（idle/processing/canceling/archived + 扩展态 waiting\_confirmation/terminated）与轮次级 `turn_status`；事件历史 SSE 回放（`evt_` Message 信封 + `event_start/event_delta` 连接级协商增量帧） |
| **Deployment**  | `dep_`   | 调度器/触发器（**不再承担版本激活**）：`schedule {cron, timezone}`（服务端计算 `upcoming_runs_at`）、`initial_events[]` 注入触发会话、状态 active/paused + 归档、手动 run 与 webhook 回调触发（`/api/v1/cloud/webhook/deployments/{token}` 入站）、运行记录 `deployment_run`（`drun_`）；cron 轮询经 DB CAS 条件更新实现多实例互斥（无需分布式锁），调度 fire lease 由 Redis 触发防重                                                         |

### 支撑资源映射

| 资源                              | 前缀                          | 说明                                                                                                                                            |
| ------------------------------- | --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| File                            | `file_`                     | 一等资源：`purpose` 五态必填、单文件 ≤50MB、内容落盘 `<app.file.storage-root>/<file_id>`；仅 `tool_output`/`skill_output` 可经 `GET /files/{id}/content` 下载（其余 403） |
| Skill                           | `skill_`                    | 技能资产原语（SKILL.md + 版本内容 `skill_content_version`），承接原 workspace\_files 技能承载                                                                     |
| Vault / Credential              | `vault_`                    | `auth.type = static_bearer \| mcp_oauth`；凭证**只写不读**（明文仅内存材料化），生命周期 archive/rotate/validate                                                    |
| Memory Store / Memory / Version | `ms_` / `mem_` / `memver_`  | Session 资源挂载（非 Agent 版本字段）；更新 OCC version 式 409；`redact` 脱敏后内容不可查询                                                                            |
| Model                           | —                           | 模型目录只读种子（`GET /cloud/models`）；`model_profile` 表降级为**内部供应商凭证配置**，不对外暴露对象 ID                                                                    |
| Session Thread / 资源行 / Outcome  | `sthr_` / `sesr_` / `outc_` | 线程快照、挂载资源行、结果评测                                                                                                                               |

### API 基础约定

游标分页 `{data, first_id, last_id, has_more[, next_page]}`（limit 默认 20、上限 100）；统一错误信封 `{"error": {message, type}, "request_id", "type": "error"}` + 语义化 HTTP 状态码；时间戳输出 RFC 3339 UTC（`Z` 后缀）；JSON 请求体 ≤4MB；资源 ID 前缀全表见上。

## 关键配置项

| 配置      | 环境变量                                                                       | 说明                                                                      |
| ------- | -------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| 模型目录    | `model-catalog.items`                                                      | 模型目录种子清单（id/efforts/context\_window 等，只读）                                |
| 内部供应商映射 | `model-catalog.provider-mappings`                                          | 「租户键（ownerId 或 `*`）→ 目录模型 id → `model_profile_id`」，解析结果仅运行时装配消费、不进对外契约  |
| 模型凭证密钥  | `APP_MODEL_ENCRYPTION_KEY`                                                 | 内部供应商凭证 AES/GCM 独立加密，**compose fail-fast 必填**（无默认值，缺失即拒绝启动）             |
| 保管库凭证密钥 | `APP_VAULT_ENCRYPTION_KEY`                                                 | VaultCredential 独立加密，与 model/datasource 密钥完全隔离，**compose fail-fast 必填** |
| 数据源密钥   | `DATASOURCE_ENCRYPTION_KEY`                                                | 数据源连接密码加密，**compose fail-fast 必填**（本地直跑后端时仍有开发默认值兜底；存量密文双读兼容已支持换钥渐进迁移）  |
| 认证密钥    | `AUTH_JWT_SECRET`                                                          | JWT HS256 签名，**compose fail-fast 必填**                                   |
| 实例标识    | `APP_INSTANCE_ID`                                                          | 可选。协调层租约持有者标识（缺省回落主机名）；多实例滚动部署时按实例槽位显式指定，启动恢复仅回收本实例残留租约                 |
| 文件存储根   | `APP_FILE_STORAGE_ROOT`                                                    | 默认 `./data/files`                                                       |
| 调度轮询    | `APP_DEPLOYMENT_SCHEDULER_*`（batch-size/poll-interval-ms/initial-delay-ms） | cron 到期轮询批量与节奏，默认 20/15000/10000                                        |

> 密钥类变量在 `docker/docker-compose.yaml` / `docker-compose.backend.yaml` 中以 `${VAR:?}` 声明为**无默认值必填**（缺失时 compose 直接报错退出），示例见 `docker/.env.example`——复制为 `docker/.env` 并替换全部占位值后再启动。

## 数据库迁移（BREAKING）

Flyway 单通道承载全库 schema（compose 不挂载任何 initdb SQL）：`V1__init_full_schema.sql` 为**单一基线最终态**（历史 V1\~V19 演进与 environment config、资源 ID 前缀、session\_thread、技能类型词汇等后续增量已全部合并进基线，迁移目录仅保留 V1）。`baseline-on-migrate: true`——存量非空库无迁移历史时自动基线（V1 跳过），全新库完整重放基线；`out-of-order: true` + `validate-on-migrate: true`（checksum 防篡改）。**基线合并属破坏性变更**：应用过旧增量链（V2\~V5）或含旧版词汇数据的存量库，须 DROP schema 重建（或先 `flyway repair` 并按当前代码语义手工改写存量数据列）；正式环境数据迁移另行立项。

## 出界能力登记（对齐裁定）

以下外部 Cloud Agent 能力面**不在本系统范围**（裁定依据见 `openspec/changes/archive/` 下 2026-09-06 原语对齐提案 design.md D12/D13）：

- **Forward Mode 全家 / Dreams**：用户裁定不纳入 Managed Agents 对齐范围。
- **Webhooks（事件外推 HTTP 回调）**：不属于 Managed API 权威资源表；事件外推由 SSE 流承担。注意区分：Deployment 的 **webhook 触发**是入站触发器（外部→本系统），与出界的「出站事件 Webhooks」无关。
- **Work 队列（self\_hosted worker 分发协议）**：本系统唯一执行平面为 AgentScope Harness + 每会话 Docker 沙箱，无外部 worker 消费者；真实协议取证已留档 design.md，供未来 self\_hosted worker 独立提案复用。`self_hosted` 环境的「创建登记」保留、装配拒绝即源于此。
- **数据源一等资源 API 面**：datasource BC 已收敛为 runtime 自定义工具装配的内部实现，不面向用户暴露独立资源端点。

