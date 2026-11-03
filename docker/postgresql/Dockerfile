# ===========================================================
# DeepDataAgent - PostgreSQL 18 镜像（三大扩展源码编译）
#
# 内置扩展（不安装 Apache AGE：图谱由应用侧原生关系表实现）：
#   1. zhparser       中文分词（依赖 SCWS 1.2.3 运行库 + dict.utf8.xdb / rules.utf8.ini 词典）
#   2. vector         pgvector 向量检索（HNSW / IVFFlat）
#   3. pg_textsearch  Timescale BM25 全文检索
#
# 关键约束：pg_textsearch 的 _PG_init 强制要求经 shared_preload_libraries 预加载，
#   未预加载时 CREATE EXTENSION 及任何 bm25 索引操作直接报错（PREREQUISITE_STATE）。
#   官方镜像 docker-entrypoint 会把 CMD 中的 -c 参数同时下发给初始化临时实例，
#   故首次建库执行 init 脚本时该扩展即可创建。
#
# ========== 启动须知（必读）==========
# 本镜像已在 CMD 内置 "postgres -c shared_preload_libraries=pg_textsearch"，
# 因此【不给任何容器参数】的启动方式开箱可用：
#   docker run -d --name pg18-ext -p 5432:5432 \
#     -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=postgres \
#     -v pg18-ext-data:/var/lib/postgresql --restart unless-stopped  <image:tag>
#   docker compose -f docker/docker-compose.infra.postgresql.yaml up -d
#
# 一旦显式给出容器命令/参数，就会【整体覆盖】镜像 CMD，此时必须自行补全预加载参数：
#   docker run -d ... <image:tag> -c shared_preload_libraries='pg_textsearch'
#   compose 等价写法：command: [ "postgres", "-c", "shared_preload_libraries=pg_textsearch" ]
#
# 覆盖 CMD 时两条硬性规则，漏一条比丢参数更严重：
#   1. 首位必须是 postgres（docker-entrypoint 仅当 $1 = postgres 才走建库+init 脚本流程，
#      换成 psql/bash 等其它程序会跳过 /docker-entrypoint-initdb.d，扩展与 zhparser_cfg 均不创建）；
#   2. 若同时要其它 -c 参数，必须把 shared_preload_libraries 一并带上，
#      否则 pg_textsearch 在运行期不可用（bm25 索引与 CREATE EXTENSION 全部报错）。
# ===========================================================

FROM postgres:18.6-bookworm AS builder

ENV PG_MAJOR=18

# 将 Debian 系统源切换为阿里云镜像，加速 APT 下载（PGDG 源保持官方地址）
RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources \
    && sed -i 's/security.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources

# 扩展编译依赖：postgresql-server-dev-${PG_MAJOR} 提供 PGXS 与服务端头文件
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        postgresql-server-dev-${PG_MAJOR} \
        git \
        ca-certificates \
        wget \
        bzip2 \
    && rm -rf /var/lib/apt/lists/*

# ---------- 0. SCWS 1.2.3（zhparser 强制依赖） ----------
# 安装至 /usr/local，zhparser 通过 SCWS_HOME=/usr/local 定位头文件与 libscws
RUN cd /tmp \
    && wget -q --no-check-certificate http://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2 \
    && tar -jxf scws-1.2.3.tar.bz2 \
    && cd scws-1.2.3 \
    && ./configure --prefix=/usr/local \
    && make \
    && make install \
    && ldconfig

# ---------- 1. zhparser（中文分词） ----------
# 上游最新 tag 为 v2.3（2025-01），未做 PG18 验证；2.4（2026-06）CI 矩阵覆盖 PG16/17/18，
# 且修复了 SRF 场景下共享 ParserState 导致的分词串扰，但上游尚未打 v2.4 tag，故取 master 分支。
ARG ZHPARSER_REF=master

RUN cd /tmp \
    && git clone --depth=1 --branch ${ZHPARSER_REF} https://github.com/amutu/zhparser.git \
    && cd zhparser \
    && SCWS_HOME=/usr/local make \
    && SCWS_HOME=/usr/local make install

# ---------- 2. pgvector v0.8.7（向量检索，修复 IVFFlat 构建缓冲区溢出 CVE-2026-103484） ----------
RUN cd /tmp \
    && git clone --depth=1 --branch v0.8.7 https://github.com/pgvector/pgvector.git \
    && cd pgvector \
    && make \
    && make install

# ---------- 3. pg_textsearch v1.5.1（BM25 全文检索，官方支持 PG 17/18） ----------
RUN cd /tmp \
    && git clone --depth=1 --branch v1.5.1 https://github.com/timescale/pg_textsearch.git \
    && cd pg_textsearch \
    && make \
    && make install

# ---------- 4. 汇总待交付产物（.so / 扩展 SQL+control / zhparser 词典 / libscws） ----------
# zhparser 的 Makefile 通过 DATA_TSEARCH 自动把 dict.utf8.xdb、rules.utf8.ini 装入 tsearch_data
RUN mkdir -p /out/lib /out/extension /out/tsearch_data /out/scws \
    && cp /usr/lib/postgresql/${PG_MAJOR}/lib/zhparser.so \
          /usr/lib/postgresql/${PG_MAJOR}/lib/vector.so \
          /usr/lib/postgresql/${PG_MAJOR}/lib/pg_textsearch.so \
          /out/lib/ \
    && cp /usr/share/postgresql/${PG_MAJOR}/extension/zhparser* \
          /usr/share/postgresql/${PG_MAJOR}/extension/vector* \
          /usr/share/postgresql/${PG_MAJOR}/extension/pg_textsearch* \
          /out/extension/ \
    && cp /usr/share/postgresql/${PG_MAJOR}/tsearch_data/dict.utf8.xdb \
          /usr/share/postgresql/${PG_MAJOR}/tsearch_data/rules.utf8.ini \
          /out/tsearch_data/ \
    && cp -P /usr/local/lib/libscws.so* /out/scws/

# ========== 运行镜像 ==========
FROM postgres:18.6-bookworm

ENV PG_MAJOR=18

# 扩展 so、control、SQL 与 zhparser 词典
COPY --from=builder /out/lib/ /usr/lib/postgresql/${PG_MAJOR}/lib/
COPY --from=builder /out/extension/ /usr/share/postgresql/${PG_MAJOR}/extension/
COPY --from=builder /out/tsearch_data/ /usr/share/postgresql/${PG_MAJOR}/tsearch_data/

# scws 运行时库（zhparser 依赖；链接期已内嵌 rpath=/usr/local/lib，此处再登记到 ldconfig）
COPY --from=builder /out/scws/ /usr/local/lib/
RUN ldconfig

# 首次启动初始化脚本：创建三大扩展并配置全文检索词性映射
COPY docker/postgresql/init/00-init-extensions.sql /docker-entrypoint-initdb.d/00-init-extensions.sql

# pg_textsearch 必须随 postmaster 预加载；此 CMD 是该参数的唯一内置来源，
# 被 docker run 容器参数或 compose 的 command 覆盖后即失效（见文件头「启动须知」）
CMD ["postgres", "-c", "shared_preload_libraries=pg_textsearch"]

EXPOSE 5432
