package com.linkroa.deepdataagent.agent.infrastructure.adapter;

import com.linkroa.deepdataagent.agent.acl.datasource.ApiConnectionInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceInfo;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcCategory;
import com.linkroa.deepdataagent.agent.acl.datasource.JdbcConnectionInfo;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
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
import com.linkroa.deepdataagent.datasource.domain.repository.ColumnInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import com.linkroa.deepdataagent.datasource.infrastructure.client.ApiPaginationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    /** Schema 缓存有效期（毫秒），避免同一会话内重复连库提取元数据 */
    private static final long SCHEMA_CACHE_TTL_MILLIS = 60_000L;

    /** 表数超过该上限时，仅返回表清单而非完整字段，避免超大 Schema 撑爆上下文 */
    private static final int MAX_TABLES_FOR_FULL_SCHEMA = 50;

    /** 按数据源 ID 索引的 Schema 缓存条目 */
    private static final class CachedSchema {
        private final String schema;
        private final long cachedAt;
        CachedSchema(String schema, long cachedAt) {
            this.schema = schema;
            this.cachedAt = cachedAt;
        }
    }

    /** Schema 缓存，键为数据源 ID */
    private final ConcurrentMap<Long, CachedSchema> schemaCache = new ConcurrentHashMap<>();

    private final DatasourceConnectionRepository repository;
    private final DatasourceConnectionStrategyFactory strategyFactory;
    private final ApiSchemaRepository apiSchemaRepository;
    private final ApiFieldRepository apiFieldRepository;
    private final ApiPaginationHandler paginationHandler;
    private final DatabaseSchemaRepository databaseSchemaRepository;
    private final TableInfoRepository tableInfoRepository;
    private final ColumnInfoRepository columnInfoRepository;

    public DatasourceGatewayAdapter(
            DatasourceConnectionRepository repository,
            DatasourceConnectionStrategyFactory strategyFactory,
            ApiSchemaRepository apiSchemaRepository,
            ApiFieldRepository apiFieldRepository,
            ApiPaginationHandler paginationHandler,
            DatabaseSchemaRepository databaseSchemaRepository,
            TableInfoRepository tableInfoRepository,
            ColumnInfoRepository columnInfoRepository) {
        this.repository = repository;
        this.strategyFactory = strategyFactory;
        this.apiSchemaRepository = apiSchemaRepository;
        this.apiFieldRepository = apiFieldRepository;
        this.paginationHandler = paginationHandler;
        this.databaseSchemaRepository = databaseSchemaRepository;
        this.tableInfoRepository = tableInfoRepository;
        this.columnInfoRepository = columnInfoRepository;
    }

    @Override
    public Optional<DatasourceInfo> findDatasource(Long id) {
        return repository.findById(id).map(this::toDatasourceInfo);
    }

    @Override
    public String extractSchema(Long datasourceId) {
        // 命中未过期的缓存则直接返回，避免重复连库提取元数据
        CachedSchema cached = schemaCache.get(datasourceId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.cachedAt < SCHEMA_CACHE_TTL_MILLIS) {
            return cached.schema;
        }

        String schema = extractSchemaInternal(datasourceId);
        schemaCache.put(datasourceId, new CachedSchema(schema, now));
        return schema;
    }

    /**
     * 执行 Schema 提取（无缓存）
     * <p>按数据源类型分别提取 JDBC 或 API 的 Schema 描述文本。</p>
     *
     * @param datasourceId 数据源 ID
     * @return Schema 描述文本
     */
    private String extractSchemaInternal(Long datasourceId) {
        DatasourceConnection connection = repository.findById(datasourceId)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在: " + datasourceId));

        if (connection.type() == DatasourceType.API) {
            return extractApiSchema(connection.id());
        }

        // 方案A：优先读取本地已同步的 JDBC 元数据，避免连远程；本地无数据时才兜底连远程
        String localSchema = extractLocalJdbcSchema(connection.id());
        if (localSchema != null) {
            return localSchema;
        }

        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(
                connection.type(), connection.subType());

        try {
            List<DatabaseSchema> schemas = strategy.extractSchemas(connection);
            if (schemas.isEmpty()) {
                throw new DeepDataAgentException("未找到数据库 schema");
            }

            StringBuilder schemaDesc = new StringBuilder();
            int tableCount = 0;
            for (DatabaseSchema schema : schemas) {
                String schemaName = schema.schemaName();
                List<TableInfo> tables = strategy.extractTables(connection, schemaName);
                tableCount += tables.size();

                for (TableInfo table : tables) {
                    schemaDesc.append("表: ").append(table.tableName());
                    if (table.tableComment() != null && !table.tableComment().isBlank()) {
                        schemaDesc.append("  -- ").append(table.tableComment());
                    }
                    schemaDesc.append("\n");

                    // 表数超限时，仅输出表清单，不展开字段，避免撑爆模型上下文
                    if (tableCount <= MAX_TABLES_FOR_FULL_SCHEMA) {
                        List<ColumnInfo> columns = strategy.extractColumns(connection, schemaName, table.tableName());
                        for (ColumnInfo column : columns) {
                            schemaDesc.append("  - ").append(column.columnName())
                                     .append(" (").append(column.dataType()).append(")");
                            if (column.columnComment() != null && !column.columnComment().isBlank()) {
                                schemaDesc.append("  -- ").append(column.columnComment());
                            }
                            schemaDesc.append("\n");
                        }
                    }
                    schemaDesc.append("\n");
                }
            }
            if (tableCount > MAX_TABLES_FOR_FULL_SCHEMA) {
                schemaDesc.append("（表数超过 ").append(MAX_TABLES_FOR_FULL_SCHEMA)
                          .append("，仅列出表清单，可按需输入关键词查看具体表字段）\n");
            }

            String result = schemaDesc.toString().strip();
            if (result.isEmpty()) {
                throw new DeepDataAgentException("数据库 schema 为空");
            }
            return result;
        } catch (DeepDataAgentException e) {
            throw e;
        } catch (Exception e) {
            log.error("提取 schema 失败: {}", e.getMessage(), e);
            throw new DeepDataAgentException("提取数据库 schema 失败: " + e.getMessage());
        }
    }

    /**
     * 从本地已同步的 JDBC 元数据重组 Schema 描述文本
     * <p>优先读取本地 SQLite 中由 syncMetadata 同步的 schema/表/字段，
     * 本地无任何 schema 时返回 null，由调用方兜底连远程提取。</p>
     *
     * @param connectionId 数据源连接 ID
     * @return 本地重组后的 Schema 文本；本地无数据时返回 null
     */
    private String extractLocalJdbcSchema(Long connectionId) {
        List<DatabaseSchema> schemas = databaseSchemaRepository.findByConnectionId(connectionId);
        if (schemas.isEmpty()) {
            return null;
        }

        StringBuilder schemaDesc = new StringBuilder();
        int tableCount = 0;
        for (DatabaseSchema schema : schemas) {
            List<TableInfo> tables = tableInfoRepository.findByDatabaseSchemaId(schema.id());
            tableCount += tables.size();

            for (TableInfo table : tables) {
                schemaDesc.append("表: ").append(table.tableName());
                if (table.tableComment() != null && !table.tableComment().isBlank()) {
                    schemaDesc.append("  -- ").append(table.tableComment());
                }
                schemaDesc.append("\n");

                // 表数超限时，仅输出表清单，不展开字段，避免撑爆模型上下文（与远程路径一致）
                if (tableCount <= MAX_TABLES_FOR_FULL_SCHEMA) {
                    List<ColumnInfo> columns = columnInfoRepository.findByTableId(table.id());
                    for (ColumnInfo column : columns) {
                        schemaDesc.append("  - ").append(column.columnName())
                                 .append(" (").append(column.dataType()).append(")");
                        if (column.columnComment() != null && !column.columnComment().isBlank()) {
                            schemaDesc.append("  -- ").append(column.columnComment());
                        }
                        schemaDesc.append("\n");
                    }
                }
                schemaDesc.append("\n");
            }
        }
        if (tableCount > MAX_TABLES_FOR_FULL_SCHEMA) {
            schemaDesc.append("（表数超过 ").append(MAX_TABLES_FOR_FULL_SCHEMA)
                      .append("，仅列出表清单，可按需输入关键词查看具体表字段）\n");
        }

        String result = schemaDesc.toString().strip();
        return result.isEmpty() ? null : result;
    }

    @Override
    public List<Map<String, Object>> executeApiQuery(Long datasourceId, String apiSchemaName, int limit) {
        ApiSchema apiSchema = apiSchemaRepository.findByConnectionIdAndName(datasourceId, apiSchemaName)
                .orElseThrow(() -> new DeepDataAgentException("API Schema 不存在: " + apiSchemaName));

        int effectiveLimit = Math.min(Math.max(limit, 1), 500);
        return paginationHandler.fetchAllPages(apiSchema, null, effectiveLimit);
    }

    private String extractApiSchema(Long connectionId) {
        List<ApiSchema> schemas = apiSchemaRepository.findByConnectionId(connectionId);
        if (schemas.isEmpty()) {
            throw new DeepDataAgentException("API 数据源未配置任何 Schema");
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
            throw new DeepDataAgentException("API Schema 为空");
        }
        return result;
    }

    private DatasourceInfo toDatasourceInfo(DatasourceConnection conn) {
        JdbcConnectionInfo jdbcConfig = null;
        if (conn.jdbcConnectionConfig() != null) {
            JdbcConnectionConfig cfg = conn.jdbcConnectionConfig();
            jdbcConfig = new JdbcConnectionInfo(
                    cfg.host(), cfg.port(), cfg.database(),
                    cfg.username(), cfg.password(), cfg.schema());
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
        if (type == DatasourceType.JDBC) return DatasourceCategory.JDBC;
        if (type == DatasourceType.API) return DatasourceCategory.API;
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    private JdbcCategory mapJdbcCategory(JdbcType jdbcType) {
        if (jdbcType == null) return null;
        if (jdbcType == JdbcType.MYSQL) return JdbcCategory.MYSQL;
        if (jdbcType == JdbcType.CLICKHOUSE) return JdbcCategory.CLICKHOUSE;
        if (jdbcType == JdbcType.POSTGRESQL) return JdbcCategory.POSTGRESQL;
        throw new IllegalArgumentException("Unsupported jdbcType: " + jdbcType);
    }
}
