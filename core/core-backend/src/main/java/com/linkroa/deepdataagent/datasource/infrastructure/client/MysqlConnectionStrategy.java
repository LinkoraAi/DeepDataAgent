package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;

/**
 * MySQL连接策略
 */
public class MysqlConnectionStrategy extends AbstractJdbcConnectionStrategy {

    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
    private static final String JDBC_PREFIX = "jdbc:mysql://";

    @Override
    public String buildJdbcUrl(DatasourceConnection connection) {
        var config = connection.jdbcConnectionConfig();
        return JDBC_PREFIX + config.host() + ":" + config.port() + "/" + config.database() + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
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
    protected String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        return "`" + identifier.replace("`", "``") + "`";
    }
}
