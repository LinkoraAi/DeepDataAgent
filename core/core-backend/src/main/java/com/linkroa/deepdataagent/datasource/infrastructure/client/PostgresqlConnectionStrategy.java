package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL 连接策略
 * <p>提供 PostgreSQL 数据源的 JDBC 连接 URL 构建、元数据提取与数据预览实现。
 * 层级结构为 database → schema → table，database 写入 URL 主路径，schema 通过
 * currentSchema 参数指定默认 schema。</p>
 */
public class PostgresqlConnectionStrategy extends AbstractJdbcConnectionStrategy {

    private static final String DRIVER_CLASS = "org.postgresql.Driver";
    private static final String JDBC_PREFIX = "jdbc:postgresql://";

    @Override
    public String buildJdbcUrl(DatasourceConnection connection) {
        var config = connection.jdbcConnectionConfig();
        return JDBC_PREFIX + config.host() + ":" + config.port() + "/" + config.database()
                + "?currentSchema=" + config.schema();
    }

    @Override
    public String getDriverClassName() {
        return DRIVER_CLASS;
    }

    @Override
    public List<DatabaseSchema> extractSchemas(DatasourceConnection connection) {
        // PostgreSQL 的 schema 独立于 database，元数据同步应使用配置的 schema（默认 public）
        String schemaName = connection.jdbcConnectionConfig().schema();
        return List.of(new DatabaseSchema(null, connection.id(), schemaName, null, null, null));
    }

    @Override
    protected String buildPreviewSql(String schemaName, String tableName, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        if (schemaName != null && !schemaName.isBlank()) {
            sql.append(quoteIdentifier(schemaName)).append(".");
        }
        sql.append(quoteIdentifier(tableName));
        sql.append(" LIMIT ").append(limit);
        return sql.toString();
    }

    @Override
    protected Object convertPreviewValue(Object value) {
        // jsonb(PGobject)、数组、hstore 等复合类型统一转为文本展示
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }

    @Override
    protected String extractTableComment(Connection conn, String schemaName, String tableName) {
        String sql = "SELECT obj_description(c.oid) AS comment "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("comment");
                }
            }
        } catch (SQLException e) {
            log.warn("查询PostgreSQL表备注失败: {}", e.getMessage());
        }
        return null;
    }
}