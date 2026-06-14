package com.linkroa.deepdataagent.agent.infrastructure.executor;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcConnectionInfo;
import com.linkroa.deepdataagent.agent.exception.DataAnalysisException;
import com.linkroa.deepdataagent.agent.infrastructure.config.DataAnalysisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 查询执行器
 * <p>基于 DatasourceInfo 的 JDBC 连接信息执行 SQL 查询，
 * 区分超时、连接失败和 SQL 语法错误，记录查询耗时。</p>
 */
@Component
public class JdbcQueryExecutor implements QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcQueryExecutor.class);

    private final DataAnalysisProperties properties;

    public JdbcQueryExecutor(DataAnalysisProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Map<String, Object>> execute(DatasourceInfo datasource, String query) {
        JdbcConnectionInfo config = datasource.jdbcConfig();
        String jdbcUrl = buildJdbcUrl(datasource.jdbcCategory(), config);

        try {
            String driverClass = getDriverClass(datasource.jdbcCategory());
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new DataAnalysisException("未找到 JDBC 驱动: " + e.getMessage());
        }

        long startTime = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.username(), config.password());
             Statement stmt = conn.createStatement()) {

            int timeoutSeconds = properties.getQuery().getTimeoutSeconds();
            int maxRows = properties.getQuery().getMaxRows();

            stmt.setQueryTimeout(timeoutSeconds);
            stmt.setFetchSize(Math.min(maxRows, 1000));
            stmt.setMaxRows(maxRows);

            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(query)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("SQL 查询执行完成，耗时: {}ms，返回 {} 行", elapsed, rows.size());
            return rows;
        } catch (SQLTimeoutException e) {
            log.error("SQL 执行超时: {}", e.getMessage());
            throw new DataAnalysisException("SQL 执行超时，请优化查询条件");
        } catch (SQLException e) {
            if (isConnectionError(e)) {
                log.error("数据库连接失败: {}", e.getMessage());
                throw new DataAnalysisException("数据库连接失败，请检查数据源配置");
            }
            log.error("SQL 执行失败: {}", e.getMessage());
            throw new DataAnalysisException("SQL 执行失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(DatasourceInfo datasource) {
        return datasource.category() == DatasourceCategory.JDBC;
    }

    private boolean isConnectionError(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("08");
    }

    private String buildJdbcUrl(JdbcCategory jdbcCategory, JdbcConnectionInfo config) {
        return switch (jdbcCategory) {
            case MYSQL -> "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    .formatted(config.host(), config.port(), config.database());
            case CLICKHOUSE -> "jdbc:clickhouse://%s:%d/%s"
                    .formatted(config.host(), config.port(), config.database());
        };
    }

    private String getDriverClass(JdbcCategory jdbcCategory) {
        return switch (jdbcCategory) {
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case CLICKHOUSE -> "com.clickhouse.jdbc.ClickHouseDriver";
        };
    }
}
