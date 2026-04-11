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

复制环境变量模板：

```bash
cp .env.example .env
```

然后执行：

```bash
docker compose up --build
```

默认端口：

- 前端：`http://localhost:8080`
- 后端：`http://localhost:18080`
- 健康检查：`http://localhost:18080/actuator/health`
