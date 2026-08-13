package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * ClickHouse连接策略
 */
public class ClickhouseConnectionStrategy extends AbstractJdbcConnectionStrategy {

    private static final String DRIVER_CLASS = "com.clickhouse.jdbc.ClickHouseDriver";
    private static final String JDBC_PREFIX = "jdbc:clickhouse://";

    @Override
    public String buildJdbcUrl(DatasourceConnection connection) {
        var config = connection.jdbcConnectionConfig();
        return JDBC_PREFIX + config.host() + ":" + config.port() + "/" + config.database();
    }

    @Override
    public String getDriverClassName() {
        return DRIVER_CLASS;
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
    protected String extractTableComment(Connection conn, String schemaName, String tableName) {
        String sql = "SELECT comment FROM system.tables WHERE database = ? AND name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("comment");
                }
            }
        } catch (SQLException e) {
            log.warn("查询ClickHouse表备注失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        return "`" + identifier.replace("`", "``") + "`";
    }
}
