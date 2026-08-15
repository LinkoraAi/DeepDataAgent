# ===========================================================
# DeepDataAgent - PostgreSQL 镜像
# 基于 zhparser 官方镜像（已内置 SCWS + zhparser 中文分词），
# pgvector 与 Apache AGE 通过 APT 预编译包安装，无需源码编译。
# 官方镜像: https://hub.docker.com/r/zhparser/zhparser
# ===========================================================

FROM zhparser/zhparser:bookworm-16

# 将 Debian 系统源切换为阿里云镜像，加速 APT 下载
RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources \
    && sed -i 's/security.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources

# 安装 pgvector 与 Apache AGE（APT 预编译包，无需源码编译）
RUN apt-get update && apt-get install -y --no-install-recommends \
        postgresql-16-pgvector \
        postgresql-16-age \
    && rm -rf /var/lib/apt/lists/*

# 首次启动初始化脚本：创建三大扩展并配置数据库级参数
# （docker-entrypoint-initdb.d 按文件名顺序执行）
COPY docker/postgresql/init/00-init-extensions.sql /docker-entrypoint-initdb.d/00-init-extensions.sql

EXPOSE 5432