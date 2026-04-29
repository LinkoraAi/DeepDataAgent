-- -----------------------------------------------------------
-- 1. 数据源连接表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS datasource_connection (
    id                        INTEGER       PRIMARY KEY AUTOINCREMENT,
    name                      TEXT          NOT NULL,
    type                      TEXT          NOT NULL,
    sub_type                  TEXT,
    status                    TEXT          NOT NULL DEFAULT '',
    jdbc_connection_config    TEXT,
    api_connection_config     TEXT,
    api_auth_config           TEXT,
    description               TEXT,
    created_at                TEXT          NOT NULL DEFAULT (datetime('now')),
    updated_at                TEXT          NOT NULL DEFAULT (datetime('now')),
    created_by                TEXT,
    updated_by                TEXT,
    is_deleted                INTEGER       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_name ON datasource_connection (name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 2. 数据库Schema表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS database_schema (
    id            INTEGER       PRIMARY KEY AUTOINCREMENT,
    connection_id INTEGER       NOT NULL,
    schema_name   TEXT          NOT NULL,
    description   TEXT,
    created_at    TEXT          NOT NULL DEFAULT (datetime('now')),
    updated_at    TEXT          NOT NULL DEFAULT (datetime('now')),
    is_deleted    INTEGER       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_connection_schema ON database_schema (connection_id, schema_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 3. 表信息表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS table_info (
    id                   INTEGER       PRIMARY KEY AUTOINCREMENT,
    database_schema_id   INTEGER       NOT NULL,
    table_name           TEXT          NOT NULL,
    table_comment        TEXT,
    table_custom_comment       TEXT,
    created_at           TEXT          NOT NULL DEFAULT (datetime('now')),
    updated_at           TEXT          NOT NULL DEFAULT (datetime('now')),
    is_deleted           INTEGER       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_database_schema_table ON table_info (database_schema_id, table_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 4. 列信息表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS column_info (
    id            INTEGER       PRIMARY KEY AUTOINCREMENT,
    table_id      INTEGER       NOT NULL,
    column_name   TEXT          NOT NULL,
    data_type     TEXT          NOT NULL,
    column_comment TEXT,
    column_custom_comment TEXT,
    created_at    TEXT          NOT NULL DEFAULT (datetime('now')),
    updated_at    TEXT          NOT NULL DEFAULT (datetime('now')),
    is_deleted    INTEGER       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_table_column ON column_info (table_id, column_name) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 5. API Schema表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_schema (
    id                    INTEGER       PRIMARY KEY AUTOINCREMENT,
    connection_id         INTEGER       NOT NULL,
    name                  TEXT          NOT NULL,
    path                  TEXT          NOT NULL,
    method                TEXT          NOT NULL DEFAULT '',
    json_path_config      TEXT,
    created_at            TEXT          NOT NULL DEFAULT (datetime('now')),
    updated_at            TEXT          NOT NULL DEFAULT (datetime('now')),
    created_by            TEXT,
    updated_by            TEXT,
    is_deleted            INTEGER       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_connection_api ON api_schema (connection_id, name, url, method) WHERE is_deleted = 0;

-- -----------------------------------------------------------
-- 6. API字段表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_field (
    id              INTEGER       PRIMARY KEY AUTOINCREMENT,
    api_schema_id   INTEGER       NOT NULL,
    original_name   TEXT          NOT NULL,
    display_name    TEXT,
    json_path       TEXT,
    field_type      TEXT          NOT NULL DEFAULT '',
    description     TEXT,
    created_at      TEXT          NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT          NOT NULL DEFAULT (datetime('now')),
    is_deleted      INTEGER       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_api_schema_field ON api_field (api_schema_id, original_name) WHERE is_deleted = 0;
