package com.linkroa.deepdataagent.datasource.domain.strategy;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;

import java.util.List;
import java.util.Map;

/**
 * 数据源连接策略接口
 * <p>定义数据源连接、元数据提取和数据预览的统一契约，
 * 不同数据源类型通过实现此接口提供各自的具体行为。</p>
 */
public interface DatasourceConnectionStrategy {

    /**
     * 测试数据源连接
     *
     * @param connection 数据源连接配置
     * @return 连接测试结果
     */
    ConnectionTestResult testConnection(DatasourceConnection connection);

    /**
     * 提取数据库Schema列表
     *
     * @param connection 数据源连接配置
     * @return 数据库Schema列表
     */
    List<DatabaseSchema> extractSchemas(DatasourceConnection connection);

    /**
     * 提取指定Schema下的表列表
     *
     * @param connection 数据源连接配置
     * @param schemaName Schema名称
     * @return 表信息列表
     */
    List<TableInfo> extractTables(DatasourceConnection connection, String schemaName);

    /**
     * 提取指定表的字段列表
     *
     * @param connection 数据源连接配置
     * @param schemaName Schema名称
     * @param tableName  表名
     * @return 字段信息列表
     */
    List<ColumnInfo> extractColumns(DatasourceConnection connection, String schemaName, String tableName);

    /**
     * 预览表数据
     *
     * @param connection 数据源连接配置
     * @param schemaName Schema名称
     * @param tableName  表名
     * @param limit      返回条数限制
     * @return 预览数据列表，每个Map代表一行数据
     */
    List<Map<String, Object>> previewData(DatasourceConnection connection, String schemaName, String tableName, int limit);

    /**
     * 连接测试结果
     */
    record ConnectionTestResult(boolean success, String message) {
        public static ConnectionTestResult ok() {
            return new ConnectionTestResult(true, "连接成功");
        }

        public static ConnectionTestResult fail(String message) {
            return new ConnectionTestResult(false, message);
        }
    }
}