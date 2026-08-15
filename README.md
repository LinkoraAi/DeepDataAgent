# DeepDataAgent

DeepDataAgent 是一个基于 AgentScope Java、OpenSandbox、Vue 3 与 TDesign 的 深度数据分析处理以及行动的 Agent 应用。

## 模块结构

- `pom.xml`: 父聚合工程，统一管理版本与插件
- `core/pom.xml`: 二级聚合，组织前后端模块
- `core/core-backend`: Spring Boot 后端，采用 DDD 领域驱动设计
- `core/core-frontend`: Vue 3 + Vite + TDesign Vue Next + TDesign Chat 前端壳
- `deploy/nginx`: 前端 Nginx 反向代理配置
- `deploy/opensandbox`: OpenSandbox Server 配置
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

配置已内置（后端连接地址与 OpenSandbox 参数位于 `core/core-backend/src/main/resources/application.yaml`），无需复制 `.env`，直接执行（`docker` 目录下提供拆分的多种编排，均需在项目根目录执行）：

| 编排文件 | 作用 | 命令 |
| --- | --- | --- |
| `docker/docker-compose.yaml` | 全量一键启动（推荐） | `docker compose -f docker/docker-compose.yaml up --build` |
| `docker/docker-compose.infra.yaml` | 基础建设汇总（PostgreSQL/Redis/OpenSandbox） | `docker compose -f docker/docker-compose.infra.yaml up --build` |
| `docker/docker-compose.infra.postgresql.yaml` | 仅 PostgreSQL | `docker compose -f docker/docker-compose.infra.postgresql.yaml up --build` |
| `docker/docker-compose.infra.redis.yaml` | 仅 Redis | `docker compose -f docker/docker-compose.infra.redis.yaml up -d` |
| `docker/docker-compose.infra.opensandbox.yaml` | 仅 OpenSandbox | `docker compose -f docker/docker-compose.infra.opensandbox.yaml up --build` |
| `docker/docker-compose.backend.yaml` | 仅后端（纯服务，需先启动基础建设） | `docker compose -f docker/docker-compose.backend.yaml up --build` |
| `docker/docker-compose.frontend.yaml` | 仅前端（纯服务，需先启动后端） | `docker compose -f docker/docker-compose.frontend.yaml up --build` |

所有编排**相互独立、不依赖任何共享网络**：服务间通过宿主机网关 `host.docker.internal` 通信（PostgreSQL/Redis/OpenSandbox/后端端口均已发布至宿主机，对应 5432/6379/8090/18080）。如需覆盖连接地址（如远程 Docker 主机的 IP），可在启动容器前设置后端环境变量 `SPRING_DATASOURCE_URL`、`REDIS_HOST`、`APP_OPENSANDBOX_DOMAIN` 等。拆分运行时按"基础建设 → 后端 → 前端"顺序启动即可。

默认端口：

- 前端：`http://localhost:8080`
- 后端：`http://localhost:18080`
- 健康检查：`http://localhost:18080/actuator/health`
