package com.linkroa.deepdataagent.datasource.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据源模块Schema初始化器
 * <p>在应用启动时自动创建数据源相关的数据库表结构。</p>
 */
@Component
public class DatasourceSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatasourceSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public DatasourceSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        log.info("开始初始化数据源模块数据库表结构...");
        createDatasourceConnectionTable();
        createDatabaseSchemaTable();
        createTableInfoTable();
        createColumnInfoTable();
        createApiSchemaTable();
        createApiFieldTable();
        createApiDataTableTable();
        log.info("数据源模块数据库表结构初始化完成");
    }

    private void createDatasourceConnectionTable() {
        jdbcTemplate.execute("""
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
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_name ON datasource_connection (name) WHERE is_deleted = 0");
    }

    private void createDatabaseSchemaTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS database_schema (
                    id            INTEGER       PRIMARY KEY AUTOINCREMENT,
                    connection_id INTEGER       NOT NULL,
                    schema_name   TEXT          NOT NULL,
                    description   TEXT,
                    created_at    TEXT          NOT NULL DEFAULT (datetime('now')),
                    updated_at    TEXT          NOT NULL DEFAULT (datetime('now')),
                    is_deleted    INTEGER       NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_connection_schema ON database_schema (connection_id, schema_name) WHERE is_deleted = 0");
    }

    private void createTableInfoTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS table_info (
                    id                   INTEGER       PRIMARY KEY AUTOINCREMENT,
                    database_schema_id   INTEGER       NOT NULL,
                    table_name           TEXT          NOT NULL,
                    table_comment        TEXT,
                    table_custom_comment TEXT,
                    created_at           TEXT          NOT NULL DEFAULT (datetime('now')),
                    updated_at           TEXT          NOT NULL DEFAULT (datetime('now')),
                    is_deleted           INTEGER       NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_database_schema_table ON table_info (database_schema_id, table_name) WHERE is_deleted = 0");
    }

    private void createColumnInfoTable() {
        jdbcTemplate.execute("""
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
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_table_column ON column_info (table_id, column_name) WHERE is_deleted = 0");
    }

    private void createApiSchemaTable() {
        jdbcTemplate.execute("""
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
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_connection_api ON api_schema (connection_id, name, path, method) WHERE is_deleted = 0");
    }

    private void createApiFieldTable() {
        jdbcTemplate.execute("""
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
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_api_schema_field ON api_field (api_schema_id, original_name) WHERE is_deleted = 0");
    }

    private void createApiDataTableTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS api_data_table (
                    id                    INTEGER       PRIMARY KEY AUTOINCREMENT,
                    connection_id         INTEGER       NOT NULL,
                    table_name            TEXT          NOT NULL,
                    description           TEXT,
                    path                  TEXT,
                    method                TEXT,
                    json_path             TEXT,
                    fields_json           TEXT,
                    pagination_config_json TEXT,
                    created_at            TEXT          NOT NULL DEFAULT (datetime('now')),
                    updated_at            TEXT          NOT NULL DEFAULT (datetime('now')),
                    is_deleted            INTEGER       NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_connection_table ON api_data_table (connection_id, table_name) WHERE is_deleted = 0");
    }
}
