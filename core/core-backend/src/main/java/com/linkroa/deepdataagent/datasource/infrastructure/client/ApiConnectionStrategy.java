package com.linkroa.deepdataagent.datasource.infrastructure.client;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ApiConnectionStrategy implements DatasourceConnectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ApiConnectionStrategy.class);

    private final ApiPaginationHandler paginationHandler;
    private final ApiSchemaRepository apiSchemaRepository;

    public ApiConnectionStrategy(ApiPaginationHandler paginationHandler, ApiSchemaRepository apiSchemaRepository) {
        this.paginationHandler = paginationHandler;
        this.apiSchemaRepository = apiSchemaRepository;
    }

    @Override
    public ConnectionTestResult testConnection(DatasourceConnection connection) {
        try {
            paginationHandler.executeOnce(connection, null, Map.of());
            return ConnectionTestResult.ok();
        } catch (Exception e) {
            log.warn("API连接测试失败: {}", e.getMessage());
            return ConnectionTestResult.fail("API连接失败: " + e.getMessage());
        }
    }

    @Override
    public List<DatabaseSchema> extractSchemas(DatasourceConnection connection) {
        return List.of(new DatabaseSchema(null, connection.id(), "default", null, null, null));
    }

    @Override
    public List<TableInfo> extractTables(DatasourceConnection connection, String schemaName) {
        return List.of();
    }

    @Override
    public List<ColumnInfo> extractColumns(DatasourceConnection connection, String schemaName, String tableName) {
        return List.of();
    }

    @Override
    public List<Map<String, Object>> previewData(DatasourceConnection connection, String schemaName, String tableName, int limit) {
        try {
            ApiSchema apiSchema = apiSchemaRepository.findByConnectionIdAndName(connection.id(), tableName)
                    .orElseThrow(() -> new IllegalArgumentException("API Schema不存在: " + tableName));

            ApiTableConfig tableConfig = new ApiTableConfig(
                    apiSchema.path(),
                    apiSchema.method(),
                    apiSchema.jsonPathConfig(),
                    null
            );

            int effectiveLimit = Math.min(Math.max(limit, 1), 100);
            PaginatedApiResult result = paginationHandler.executeOnce(connection, tableConfig, Map.of());
            return result.data().stream()
                    .limit(effectiveLimit)
                    .toList();
        } catch (Exception e) {
            log.error("API数据预览失败: {}", e.getMessage());
            throw new RuntimeException("API数据预览失败: " + e.getMessage(), e);
        }
    }
}
