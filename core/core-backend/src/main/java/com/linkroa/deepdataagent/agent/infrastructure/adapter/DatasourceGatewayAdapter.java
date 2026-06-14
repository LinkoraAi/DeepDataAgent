package com.linkroa.deepdataagent.agent.infrastructure.adapter;

import com.linkroa.deepdataagent.agent.acl.datasource.ApiConnectionInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcConnectionInfo;
import com.linkroa.deepdataagent.agent.exception.DataAnalysisException;
import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.model.JdbcConnectionConfig;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiFieldRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import com.linkroa.deepdataagent.datasource.infrastructure.client.ApiPaginationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源网关适配器
 * <p>实现 DatasourceGateway 接口，将 datasource 模块的领域模型
 * 翻译为 agent 模块的 ACL 值对象，遵循防腐层模式。</p>
 *
 * <p>此适配器是 agent 模块中唯一引用 datasource 模块类的组件，
 * 确保跨限界上下文的依赖被隔离在 infrastructure 层。</p>
 */
@Component
public class DatasourceGatewayAdapter implements DatasourceGateway {

    private static final Logger log = LoggerFactory.getLogger(DatasourceGatewayAdapter.class);

    private final DatasourceConnectionRepository repository;
    private final DatasourceConnectionStrategyFactory strategyFactory;
    private final ApiSchemaRepository apiSchemaRepository;
    private final ApiFieldRepository apiFieldRepository;
    private final ApiPaginationHandler paginationHandler;

    public DatasourceGatewayAdapter(
            DatasourceConnectionRepository repository,
            DatasourceConnectionStrategyFactory strategyFactory,
            ApiSchemaRepository apiSchemaRepository,
            ApiFieldRepository apiFieldRepository,
            ApiPaginationHandler paginationHandler) {
        this.repository = repository;
        this.strategyFactory = strategyFactory;
        this.apiSchemaRepository = apiSchemaRepository;
        this.apiFieldRepository = apiFieldRepository;
        this.paginationHandler = paginationHandler;
    }

    @Override
    public Optional<DatasourceInfo> findDatasource(Long id) {
        return repository.findById(id).map(this::toDatasourceInfo);
    }

    @Override
    public String extractSchema(Long datasourceId) {
        DatasourceConnection connection = repository.findById(datasourceId)
                .orElseThrow(() -> new DataAnalysisException("数据源不存在: " + datasourceId));

        if (connection.type() == DatasourceType.API) {
            return extractApiSchema(connection.id());
        }

        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(
                connection.type(), connection.subType());

        try {
            List<DatabaseSchema> schemas = strategy.extractSchemas(connection);
            if (schemas.isEmpty()) {
                throw new DataAnalysisException("未找到数据库 schema");
            }

            StringBuilder schemaDesc = new StringBuilder();
            for (DatabaseSchema schema : schemas) {
                String schemaName = schema.schemaName();
                List<TableInfo> tables = strategy.extractTables(connection, schemaName);

                for (TableInfo table : tables) {
                    schemaDesc.append("表: ").append(table.tableName());
                    if (table.tableComment() != null && !table.tableComment().isBlank()) {
                        schemaDesc.append("  -- ").append(table.tableComment());
                    }
                    schemaDesc.append("\n");

                    List<ColumnInfo> columns = strategy.extractColumns(connection, schemaName, table.tableName());
                    for (ColumnInfo column : columns) {
                        schemaDesc.append("  - ").append(column.columnName())
                                 .append(" (").append(column.dataType()).append(")");
                        if (column.columnComment() != null && !column.columnComment().isBlank()) {
                            schemaDesc.append("  -- ").append(column.columnComment());
                        }
                        schemaDesc.append("\n");
                    }
                    schemaDesc.append("\n");
                }
            }

            String result = schemaDesc.toString().strip();
            if (result.isEmpty()) {
                throw new DataAnalysisException("数据库 schema 为空");
            }
            return result;
        } catch (DataAnalysisException e) {
            throw e;
        } catch (Exception e) {
            log.error("提取 schema 失败: {}", e.getMessage(), e);
            throw new DataAnalysisException("提取数据库 schema 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> executeApiQuery(Long datasourceId, String apiSchemaName, int limit) {
        ApiSchema apiSchema = apiSchemaRepository.findByConnectionIdAndName(datasourceId, apiSchemaName)
                .orElseThrow(() -> new DataAnalysisException("API Schema 不存在: " + apiSchemaName));

        int effectiveLimit = Math.min(Math.max(limit, 1), 1000);
        return paginationHandler.fetchAllPages(apiSchema, null, effectiveLimit);
    }

    private String extractApiSchema(Long connectionId) {
        List<ApiSchema> schemas = apiSchemaRepository.findByConnectionId(connectionId);
        if (schemas.isEmpty()) {
            throw new DataAnalysisException("API 数据源未配置任何 Schema");
        }

        StringBuilder schemaDesc = new StringBuilder();
        for (ApiSchema schema : schemas) {
            schemaDesc.append("表: ").append(schema.name());
            if (schema.url() != null) {
                schemaDesc.append("  -- API: ").append(schema.method()).append(" ").append(schema.url());
            }
            schemaDesc.append("\n");

            List<ApiField> fields = apiFieldRepository.findByApiSchemaId(schema.id());
            for (ApiField field : fields) {
                schemaDesc.append("  - ").append(field.displayName());
                if (field.originalName() != null && !field.originalName().equals(field.displayName())) {
                    schemaDesc.append(" (").append(field.originalName()).append(")");
                }
                schemaDesc.append(" (").append(field.fieldType()).append(")");
                if (field.description() != null && !field.description().isBlank()) {
                    schemaDesc.append("  -- ").append(field.description());
                }
                schemaDesc.append("\n");
            }
            schemaDesc.append("\n");
        }

        String result = schemaDesc.toString().strip();
        if (result.isEmpty()) {
            throw new DataAnalysisException("API Schema 为空");
        }
        return result;
    }

    private DatasourceInfo toDatasourceInfo(DatasourceConnection conn) {
        JdbcConnectionInfo jdbcConfig = null;
        if (conn.jdbcConnectionConfig() != null) {
            JdbcConnectionConfig cfg = conn.jdbcConnectionConfig();
            jdbcConfig = new JdbcConnectionInfo(
                    cfg.host(), cfg.port(), cfg.database(),
                    cfg.username(), cfg.password());
        }

        ApiConnectionInfo apiConfig = null;
        if (conn.type() == DatasourceType.API) {
            List<String> schemaNames = apiSchemaRepository.findByConnectionId(conn.id())
                    .stream().map(ApiSchema::name).toList();
            apiConfig = new ApiConnectionInfo(conn.id(), schemaNames);
        }

        return new DatasourceInfo(
                conn.id(),
                conn.name(),
                mapCategory(conn.type()),
                mapJdbcCategory(conn.subType()),
                conn.status() == DatasourceStatus.ENABLED,
                jdbcConfig,
                apiConfig
        );
    }

    private DatasourceCategory mapCategory(DatasourceType type) {
        return switch (type) {
            case JDBC -> DatasourceCategory.JDBC;
            case API -> DatasourceCategory.API;
        };
    }

    private JdbcCategory mapJdbcCategory(JdbcType jdbcType) {
        if (jdbcType == null) return null;
        return switch (jdbcType) {
            case MYSQL -> JdbcCategory.MYSQL;
            case CLICKHOUSE -> JdbcCategory.CLICKHOUSE;
        };
    }
}
