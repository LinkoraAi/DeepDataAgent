-- -----------------------------------------------------------
-- Flyway 基线迁移 v1：数据源元数据建模
-- 表：datasource_connection / database_schema / table_info /
--      column_info / api_schema / api_field
-- 约定：V{n}__{描述}.sql 一经应用即不可修改（checksum 校验），
--       后续任何变更请新增 V{n+1}__{描述}.sql。
-- 说明：列注释与表注释由领域实体与文档维护，不在迁移脚本中重复维护；
--       时间列沿用 VARCHAR(19) 的既有约定，避免行为变更。
-- -----------------------------------------------------------

-- -----------------------------------------------------------
-- 1. 数据源连接表
-- -----------------------------------------------------------
CREATE TABLE datasource_connection (
    id                     BIGSERIAL    PRIMARY KEY,
    name                   VARCHAR(100) NOT NULL,
    type                   VARCHAR(30)  NOT NULL,
    sub_type               VARCHAR(50),
    status                 VARCHAR(20)  NOT NULL DEFAULT '',
    jdbc_connection_config JSONB,
    description            VARCHAR(500),
    created_at             VARCHAR(19)  NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    updated_at             VARCHAR(19)  NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    created_by             VARCHAR(100),
    updated_by             VARCHAR(100),
    is_deleted             SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_name ON datasource_connection (name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 2. 数据库Schema表
-- -----------------------------------------------------------
CREATE TABLE database_schema (
    id            BIGSERIAL     PRIMARY KEY,
    connection_id BIGINT        NOT NULL,
    schema_name   VARCHAR(100)  NOT NULL,
    description   VARCHAR(500),
    created_at    VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    updated_at    VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_connection_schema ON database_schema (connection_id, schema_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 3. 表信息表
-- -----------------------------------------------------------
CREATE TABLE table_info (
    id                   BIGSERIAL     PRIMARY KEY,
    database_schema_id   BIGINT        NOT NULL,
    table_name           VARCHAR(100)  NOT NULL,
    table_comment        VARCHAR(500),
    table_custom_comment VARCHAR(500),
    created_at           VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    updated_at           VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    is_deleted           SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_database_schema_table ON table_info (database_schema_id, table_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 4. 列信息表
-- -----------------------------------------------------------
CREATE TABLE column_info (
    id                    BIGSERIAL     PRIMARY KEY,
    table_id              BIGINT        NOT NULL,
    column_name           VARCHAR(100)  NOT NULL,
    data_type             VARCHAR(50)   NOT NULL,
    column_comment        VARCHAR(500),
    column_custom_comment VARCHAR(500),
    created_at            VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    updated_at            VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    is_deleted            SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_table_column ON column_info (table_id, column_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 5. API Schema表（config 为 JSONB）
-- -----------------------------------------------------------
CREATE TABLE api_schema (
    id            BIGSERIAL     PRIMARY KEY,
    connection_id BIGINT        NOT NULL,
    name          VARCHAR(100)  NOT NULL,
    url           VARCHAR(1000) NOT NULL,
    method        VARCHAR(10)   NOT NULL DEFAULT 'GET',
    config        JSONB,
    created_at    VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    updated_at    VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_connection_api ON api_schema (connection_id, url, method) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 6. API字段表
-- -----------------------------------------------------------
CREATE TABLE api_field (
    id            BIGSERIAL     PRIMARY KEY,
    api_schema_id BIGINT        NOT NULL,
    original_name VARCHAR(100)  NOT NULL,
    display_name  VARCHAR(100),
    json_path     VARCHAR(500),
    field_type    VARCHAR(50)   NOT NULL DEFAULT '',
    description   VARCHAR(500),
    created_at    VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    updated_at    VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_api_schema_field ON api_field (api_schema_id, original_name) WHERE is_deleted = 0;