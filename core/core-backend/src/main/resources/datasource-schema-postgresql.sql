-- -----------------------------------------------------------
-- 1. 数据源连接表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS datasource_connection (
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

COMMENT ON TABLE datasource_connection IS '数据源连接表';
COMMENT ON COLUMN datasource_connection.id IS '主键，自增';
COMMENT ON COLUMN datasource_connection.name IS '数据源名称';
COMMENT ON COLUMN datasource_connection.type IS '数据源类型（JDBC/API）';
COMMENT ON COLUMN datasource_connection.sub_type IS '子类型（如 MySQL/ClickHouse）';
COMMENT ON COLUMN datasource_connection.status IS '数据源状态';
COMMENT ON COLUMN datasource_connection.jdbc_connection_config IS 'JDBC 连接配置（JSONB：host/port/database/username/加密 password）';
COMMENT ON COLUMN datasource_connection.description IS '数据源描述';
COMMENT ON COLUMN datasource_connection.created_at IS '创建时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN datasource_connection.updated_at IS '更新时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN datasource_connection.created_by IS '创建人';
COMMENT ON COLUMN datasource_connection.updated_by IS '更新人';
COMMENT ON COLUMN datasource_connection.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_name ON datasource_connection (name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 2. 数据库Schema表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS database_schema (
    id            BIGSERIAL     PRIMARY KEY,
    connection_id BIGINT        NOT NULL,
    schema_name   VARCHAR(100)  NOT NULL,
    description   VARCHAR(500),
    created_at    VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    updated_at    VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    is_deleted    SMALLINT      NOT NULL DEFAULT 0
);

COMMENT ON TABLE database_schema IS '数据库Schema表';
COMMENT ON COLUMN database_schema.id IS '主键，自增';
COMMENT ON COLUMN database_schema.connection_id IS '关联的数据源连接 ID';
COMMENT ON COLUMN database_schema.schema_name IS 'Schema 名称';
COMMENT ON COLUMN database_schema.description IS 'Schema 描述';
COMMENT ON COLUMN database_schema.created_at IS '创建时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN database_schema.updated_at IS '更新时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN database_schema.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_connection_schema ON database_schema (connection_id, schema_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 3. 表信息表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS table_info (
    id                   BIGSERIAL     PRIMARY KEY,
    database_schema_id   BIGINT        NOT NULL,
    table_name           VARCHAR(100)  NOT NULL,
    table_comment        VARCHAR(500),
    table_custom_comment VARCHAR(500),
    created_at           VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    updated_at           VARCHAR(19)   NOT NULL DEFAULT (to_char(now(), 'YYYY-MM-DD HH24:MI:SS')),
    is_deleted           SMALLINT      NOT NULL DEFAULT 0
);

COMMENT ON TABLE table_info IS '表信息表';
COMMENT ON COLUMN table_info.id IS '主键，自增';
COMMENT ON COLUMN table_info.database_schema_id IS '关联的 Schema ID';
COMMENT ON COLUMN table_info.table_name IS '表名';
COMMENT ON COLUMN table_info.table_comment IS '表注释（数据库自带）';
COMMENT ON COLUMN table_info.table_custom_comment IS '表自定义注释（用户维护）';
COMMENT ON COLUMN table_info.created_at IS '创建时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN table_info.updated_at IS '更新时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN table_info.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_database_schema_table ON table_info (database_schema_id, table_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 4. 列信息表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS column_info (
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

COMMENT ON TABLE column_info IS '列信息表';
COMMENT ON COLUMN column_info.id IS '主键，自增';
COMMENT ON COLUMN column_info.table_id IS '关联的表信息 ID';
COMMENT ON COLUMN column_info.column_name IS '列名';
COMMENT ON COLUMN column_info.data_type IS '列数据类型';
COMMENT ON COLUMN column_info.column_comment IS '列注释（数据库自带）';
COMMENT ON COLUMN column_info.column_custom_comment IS '列自定义注释（用户维护）';
COMMENT ON COLUMN column_info.created_at IS '创建时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN column_info.updated_at IS '更新时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN column_info.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_table_column ON column_info (table_id, column_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 5. API Schema表（config 为 JSONB）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_schema (
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

COMMENT ON TABLE api_schema IS 'API Schema 表：独立列存索引/查询字段，config 列 JSON 聚合其余所有配置';
COMMENT ON COLUMN api_schema.id IS '主键，自增';
COMMENT ON COLUMN api_schema.connection_id IS '关联的数据源连接 ID';
COMMENT ON COLUMN api_schema.name IS 'API 名称';
COMMENT ON COLUMN api_schema.url IS 'API 请求地址';
COMMENT ON COLUMN api_schema.method IS '请求方法（GET/POST 等）';
COMMENT ON COLUMN api_schema.config IS 'API 配置聚合（JSONB：headers/params/body/bodyType/jsonPathConfig/timeout/retryCount/authConfig/paginationConfig/preOperationConfigs）';
COMMENT ON COLUMN api_schema.created_at IS '创建时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN api_schema.updated_at IS '更新时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN api_schema.created_by IS '创建人';
COMMENT ON COLUMN api_schema.updated_by IS '更新人';
COMMENT ON COLUMN api_schema.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_connection_api ON api_schema (connection_id, url, method) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 6. API字段表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_field (
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

COMMENT ON TABLE api_field IS 'API 字段表';
COMMENT ON COLUMN api_field.id IS '主键，自增';
COMMENT ON COLUMN api_field.api_schema_id IS '关联的 API Schema ID';
COMMENT ON COLUMN api_field.original_name IS '原始字段名';
COMMENT ON COLUMN api_field.display_name IS '展示名';
COMMENT ON COLUMN api_field.json_path IS 'JSONPath 表达式';
COMMENT ON COLUMN api_field.field_type IS '字段类型';
COMMENT ON COLUMN api_field.description IS '字段描述';
COMMENT ON COLUMN api_field.created_at IS '创建时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN api_field.updated_at IS '更新时间（yyyy-MM-dd HH:mm:ss）';
COMMENT ON COLUMN api_field.is_deleted IS '逻辑删除标记（1=已删除，0=未删除）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_api_schema_field ON api_field (api_schema_id, original_name) WHERE is_deleted = 0;
