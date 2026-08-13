package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽象JDBC连接策略
 * <p>提供JDBC数据源的通用连接、元数据提取和数据预览实现，
 * 子类只需提供JDBC URL构建逻辑和驱动类名。</p>
 */
public abstract class AbstractJdbcConnectionStrategy implements DatasourceConnectionStrategy {

    protected static final Logger log = LoggerFactory.getLogger(AbstractJdbcConnectionStrategy.class);

    private static final int CONNECTION_TIMEOUT_SECONDS = 10;
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    public abstract String buildJdbcUrl(DatasourceConnection connection);

    public abstract String getDriverClassName();

    /**
     * 获取 JDBC 元数据查询使用的 catalog（数据库名）
     * <p>MySQL 中 catalog 即数据库名；PostgreSQL 中 catalog 为数据库名，schema 为 schema 名，
     * 二者需分开传入 getTables/getColumns，否则 PostgreSQL 会按 schema 名去查 catalog 导致查不到表。</p>
     *
     * @param connection 数据源连接
     * @return catalog 名称（数据库名）
     */
    protected String getCatalogName(DatasourceConnection connection) {
        return connection.jdbcConnectionConfig().database();
    }

    protected abstract String buildPreviewSql(String schemaName, String tableName, int limit);

    @Override
    public ConnectionTestResult testConnection(DatasourceConnection connection) {
        String url = buildJdbcUrl(connection);
        try {
            Class.forName(getDriverClassName());
        } catch (ClassNotFoundException e) {
            return ConnectionTestResult.fail("未找到JDBC驱动: " + getDriverClassName());
        }
        try (Connection conn = DriverManager.getConnection(url, connection.jdbcConnectionConfig().username(), connection.jdbcConnectionConfig().password())) {
            if (conn.isValid(CONNECTION_TIMEOUT_SECONDS)) {
                return ConnectionTestResult.ok();
            }
            return ConnectionTestResult.fail("连接验证失败");
        } catch (SQLException e) {
            log.warn("JDBC连接测试失败: {}", e.getMessage());
            return ConnectionTestResult.fail(diagnoseSqlException(e));
        }
    }

    @Override
    public List<DatabaseSchema> extractSchemas(DatasourceConnection connection) {
        String url = buildJdbcUrl(connection);
        List<DatabaseSchema> schemas = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, connection.jdbcConnectionConfig().username(), connection.jdbcConnectionConfig().password())) {
            // 对于MySQL，只返回配置中指定的数据库
            String configuredDatabase = connection.jdbcConnectionConfig().database();
            if (StringUtils.isBlank(configuredDatabase)) {
                throw new RuntimeException("需要指定同步的数据库名称");
            }
            schemas.add(new DatabaseSchema(null, connection.id(), configuredDatabase, null, null, null));
        } catch (SQLException e) {
            log.error("提取数据库Schema列表失败: {}", e.getMessage());
            throw new RuntimeException("提取数据库Schema列表失败: " + e.getMessage(), e);
        }
        return schemas;
    }

    @Override
    public List<TableInfo> extractTables(DatasourceConnection connection, String schemaName) {
        String url = buildJdbcUrl(connection);
        List<TableInfo> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, connection.jdbcConnectionConfig().username(), connection.jdbcConnectionConfig().password())) {
            ResultSet rs = conn.getMetaData().getTables(getCatalogName(connection), schemaName, "%", new String[]{"TABLE"});
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableComment = rs.getString("REMARKS");
                if (tableComment == null) {
                    tableComment = extractTableComment(conn, schemaName, tableName);
                }
                tables.add(new TableInfo(null, null, tableName, tableComment, null, null, null));
            }
        } catch (SQLException e) {
            log.error("提取表列表失败: {}", e.getMessage());
            throw new RuntimeException("提取表列表失败: " + e.getMessage(), e);
        }
        return tables;
    }

    @Override
    public List<ColumnInfo> extractColumns(DatasourceConnection connection, String schemaName, String tableName) {
        String url = buildJdbcUrl(connection);
        List<ColumnInfo> columns = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, connection.jdbcConnectionConfig().username(), connection.jdbcConnectionConfig().password())) {
            ResultSet rs = conn.getMetaData().getColumns(getCatalogName(connection), schemaName, tableName, "%");
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String dataType = rs.getString("TYPE_NAME");
                String columnComment = rs.getString("REMARKS");
                columns.add(new ColumnInfo(null, null, columnName, dataType, columnComment, null, null, null));
            }
        } catch (SQLException e) {
            log.error("提取字段信息失败: {}", e.getMessage());
            throw new RuntimeException("提取字段信息失败: " + e.getMessage(), e);
        }
        return columns;
    }

    @Override
    public List<Map<String, Object>> previewData(DatasourceConnection connection, String schemaName, String tableName, int limit) {
        String url = buildJdbcUrl(connection);
        List<Map<String, Object>> rows = new ArrayList<>();
        
        try {
            Class.forName(getDriverClassName());
        } catch (ClassNotFoundException e) {
            log.warn("未找到JDBC驱动: {}", e.getMessage());
            throw new RuntimeException("未找到JDBC驱动: " + getDriverClassName(), e);
        }
        
        String sql = buildPreviewSql(schemaName, tableName, limit);
        log.info("执行预览SQL: {}", sql);
        
        try (Connection conn = DriverManager.getConnection(url, connection.jdbcConnectionConfig().username(), connection.jdbcConnectionConfig().password());
             Statement stmt = conn.createStatement()) {
            
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setFetchSize(Math.min(limit, 1000));
            
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnLabel(i), convertPreviewValue(rs.getObject(i)));
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            log.error("预览数据失败: {}", e.getMessage());
            throw new RuntimeException("预览数据失败: " + e.getMessage(), e);
        }
        return rows;
    }

    protected String extractTableComment(Connection conn, String schemaName, String tableName) {
        return null;
    }

    protected String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * 转换预览数据中的单元格值
     * <p>默认原样返回，PostgreSQL 等子类可覆写以将复合类型（jsonb、数组等）转为文本展示。</p>
     *
     * @param value 原始单元格值
     * @return 转换后的值
     */
    protected Object convertPreviewValue(Object value) {
        return value;
    }

    /**
     * 将 SQL 异常转换为可读的中文连接诊断信息
     * <p>按 SQLState 与消息关键字识别常见连接失败场景，避免直接暴露底层原始错误。</p>
     *
     * @param e SQL 异常
     * @return 诊断后的中文提示
     */
    String diagnoseSqlException(SQLException e) {
        String state = e.getSQLState();
        if (state != null) {
            if (state.startsWith("08")) {
                return "网络连接失败，请检查主机地址和端口号";
            }
            // 28000/08004 通用认证失败，28P01 PostgreSQL 密码认证失败(invalid_password)
            if ("28000".equals(state) || "08004".equals(state) || "28P01".equals(state)) {
                return "身份认证失败，请检查用户名和密码";
            }
            // PostgreSQL: 3D000 目标 schema 不存在，42501 schema 无访问权限
            if ("3D000".equals(state)) {
                return "目标 schema 不存在，请检查 schema 配置";
            }
            if ("42501".equals(state)) {
                return "目标 schema 无访问权限，请检查 schema 配置或用户权限";
            }
        }
        if (e.getMessage() != null) {
            String msg = e.getMessage().toLowerCase();
            if (msg.contains("timeout") || msg.contains("timed out")) {
                return "连接超时，请检查网络或目标数据源状态";
            }
            if (msg.contains("unknown database") || msg.contains("database") && msg.contains("not found")) {
                return "数据库不存在，请检查数据库名称";
            }
            // PostgreSQL 的 schema 不存在错误信息
            if (msg.contains("schema") && msg.contains("does not exist")) {
                return "目标 schema 不存在，请检查 schema 配置";
            }
            if (msg.contains("access denied") || msg.contains("authentication failed")) {
                return "身份认证失败，请检查用户名和密码";
            }
            if (msg.contains("connection refused") || msg.contains("network is unreachable")) {
                return "网络连接失败，请检查主机地址和端口号";
            }
        }
        return "连接失败: " + e.getMessage();
    }
}
