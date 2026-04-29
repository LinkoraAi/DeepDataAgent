package com.linkroa.deepdataagent.datasource.application.service;

import com.linkroa.deepdataagent.datasource.application.assembler.DatasourceAssembler;
import com.linkroa.deepdataagent.datasource.application.command.*;
import com.linkroa.deepdataagent.datasource.controller.response.DatasourceTypeResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ParseApiResponseResult;
import com.linkroa.deepdataagent.datasource.controller.response.ParsedFieldResponse;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiFieldRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.ColumnInfoRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.client.ApiPaginationHandler;
import com.linkroa.deepdataagent.datasource.domain.service.DatasourceConnectionDomainService;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import com.linkroa.deepdataagent.datasource.infrastructure.adapter.ApiResponseParser;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据源应用服务
 * <p>编排数据源管理的核心用例，协调领域服务和仓储完成业务操作。</p>
 */
@Service
public class DatasourceApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceApplicationService.class);

    private final DatasourceConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository databaseSchemaRepository;
    private final TableInfoRepository tableInfoRepository;
    private final ColumnInfoRepository columnInfoRepository;
    private final ApiSchemaRepository apiSchemaRepository;
    private final ApiFieldRepository apiFieldRepository;
    private final DatasourceConnectionStrategyFactory strategyFactory;
    private final DatasourceConnectionDomainService domainService;
    private final TransactionTemplate transactionTemplate;
    private final ApiResponseParser apiResponseParser;
    private final ApiPaginationHandler apiPaginationHandler;

    public DatasourceApplicationService(
            DatasourceConnectionRepository connectionRepository,
            DatabaseSchemaRepository databaseSchemaRepository,
            TableInfoRepository tableInfoRepository,
            ColumnInfoRepository columnInfoRepository,
            ApiSchemaRepository apiSchemaRepository,
            ApiFieldRepository apiFieldRepository,
            DatasourceConnectionStrategyFactory strategyFactory,
            DatasourceConnectionDomainService domainService,
            TransactionTemplate transactionTemplate,
            ApiResponseParser apiResponseParser,
            ApiPaginationHandler apiPaginationHandler
    ) {
        this.connectionRepository = connectionRepository;
        this.databaseSchemaRepository = databaseSchemaRepository;
        this.tableInfoRepository = tableInfoRepository;
        this.columnInfoRepository = columnInfoRepository;
        this.apiSchemaRepository = apiSchemaRepository;
        this.apiFieldRepository = apiFieldRepository;
        this.strategyFactory = strategyFactory;
        this.domainService = domainService;
        this.transactionTemplate = transactionTemplate;
        this.apiResponseParser = apiResponseParser;
        this.apiPaginationHandler = apiPaginationHandler;
    }

    /**
     * 获取支持的数据源类型列表
     * <p>平铺展示所有可选的具体数据源类型（MySQL、ClickHouse、API）。</p>
     */
    public List<DatasourceTypeResponse> getSupportedTypes() {
        List<DatasourceTypeResponse> types = new ArrayList<>();
        for (DatasourceTypeEnum typeEnum : DatasourceTypeEnum.values()) {
            types.add(new DatasourceTypeResponse(
                    typeEnum.getType(),
                    typeEnum.getSubType(),
                    typeEnum.getName(),
                    typeEnum.getCategory()
            ));
        }
        return types;
    }

    public DatasourceConnection createDatasource(CreateDatasourceCommand command) {
        if (connectionRepository.findByName(command.name()).isPresent()) {
            throw new DeepDataAgentException("数据源名称已被使用");
        }

        DatasourceConnection connection = DatasourceAssembler.toDatasourceConnection(command);

        return transactionTemplate.execute(status -> {
            DatasourceConnection saved = connectionRepository.save(connection);
            
            if (ObjectUtils.isNotEmpty(saved)) {
                if (saved.type() == DatasourceType.API && ObjectUtils.isNotEmpty(command.apiConfig())) {
                    saveApiMetadata(saved.id(), command.apiConfig());
                } else if (saved.type() == DatasourceType.JDBC) {
                    doSyncMetadata(saved);
                }
            }
            
            return saved;
        });
    }
    
    private void saveApiMetadata(Long connectionId, ApiConfigCommand config) {
        if (StringUtils.isBlank(config.schemaName()) || StringUtils.isBlank(config.schemaPath())) {
            return;
        }
        
        ApiSchema schema = new ApiSchema(
            null,
            connectionId,
            config.schemaName(),
            config.schemaPath(),
            config.schemaMethod() != null ? config.schemaMethod() : HttpMethod.GET,
            config.jsonPathConfig(),
            LocalDateTime.now(), LocalDateTime.now(), null, null
        );
        ApiSchema savedSchema = apiSchemaRepository.save(schema);
        
        if (config.fields() != null && !config.fields().isEmpty()) {
            for (var f : config.fields()) {
                ApiField field = new ApiField(
                    null,
                    savedSchema.id(),
                    f.originalName(),
                    f.displayName(),
                    f.jsonPath(),
                    f.fieldType(),
                    f.description(),
                    LocalDateTime.now(),
                    LocalDateTime.now()
                );
                apiFieldRepository.save(field);
            }
        }
    }

    public DatasourceConnection updateDatasource(UpdateDatasourceCommand command) {
        DatasourceConnection existing = connectionRepository.findById(command.id())
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));

        if (command.name() != null && !command.name().equals(existing.name())) {
            connectionRepository.findByName(command.name())
                    .filter(c -> !c.id().equals(existing.id()))
                    .ifPresent(c -> {
                        throw new DeepDataAgentException("数据源名称已被使用");
                    });
        }

        DatasourceConnection updated = DatasourceAssembler.toDatasourceConnection(command, existing);

        boolean connectionConfigChanged = isConnectionConfigChanged(existing, updated);

        return transactionTemplate.execute(status -> {
            DatasourceConnection saved = connectionRepository.update(updated);
            
            if (ObjectUtils.isNotEmpty(saved)) {
                if (saved.type() == DatasourceType.API && ObjectUtils.isNotEmpty(command.apiConfig())) {
                    updateApiSchemaAndFields(saved.id(), command.apiConfig());
                } else if (saved.type() == DatasourceType.JDBC && connectionConfigChanged) {
                    doSyncMetadata(saved);
                }
            }
            
            return saved;
        });
    }
    
    private void updateApiSchemaAndFields(Long connectionId, ApiConfigCommand config) {
        if (StringUtils.isBlank(config.schemaName()) || StringUtils.isBlank(config.schemaPath())) {
            return;
        }
        
        List<ApiSchema> existingSchemas = apiSchemaRepository.findByConnectionId(connectionId);
        for (ApiSchema schema : existingSchemas) {
            apiFieldRepository.deleteByApiSchemaId(schema.id());
        }
        apiSchemaRepository.deleteByConnectionId(connectionId);

        saveApiMetadata(connectionId, config);
    }

    public void enableDatasource(Long id) {
        DatasourceConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));

        domainService.validateCanEnable(connection);

        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());
        DatasourceConnectionStrategy.ConnectionTestResult result = strategy.testConnection(connection);
        if (!result.success()) {
            throw new DeepDataAgentException("连接测试失败: " + result.message());
        }

        transactionTemplate.executeWithoutResult(status -> connectionRepository.updateStatus(id, DatasourceStatus.ENABLED));
    }

    public void disableDatasource(Long id) {
        DatasourceConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));

        domainService.validateCanDisable(connection);

        transactionTemplate.executeWithoutResult(status -> connectionRepository.updateStatus(id, DatasourceStatus.DISABLED));
    }

    public void deleteDatasource(Long id) {
        DatasourceConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));

        domainService.validateCanDelete(connection);

        transactionTemplate.executeWithoutResult(status -> {
            deleteRelatedMetadata(connection);
            connectionRepository.deleteById(id);
        });
    }

    public DatasourceConnectionStrategy.ConnectionTestResult testConnection(TestConnectionCommand command) {
        if (command.id() != null) {
            DatasourceConnection connection = connectionRepository.findById(command.id())
                    .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));
            DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());
            return strategy.testConnection(connection);
        }

        DatasourceType type = DatasourceType.valueOf(command.type());
        JdbcType subType = command.subType() != null ? JdbcType.valueOf(command.subType()) : null;
        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(type, subType);

        DatasourceConnection tempConnection = buildTempConnection(command);
        return strategy.testConnection(tempConnection);
    }

    public void frontendSyncMetadata(Long connectionId) {
        DatasourceConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));

        domainService.validateCanSync(connection);

        transactionTemplate.executeWithoutResult(status -> doSyncMetadata(connection));
    }

    public PaginatedResult<DatasourceConnection> listDatasources(ListDatasourceQuery query) {
        List<DatasourceConnection> connections = connectionRepository.findByCondition(
                query.keyword(), query.type(), query.status(), query.page(), query.size()
        );
        long total = connectionRepository.countByCondition(query.keyword(), query.type(), query.status());
        return new PaginatedResult<>(connections, total, query.page(), query.size());
    }

    public DatasourceConnection getDatasource(Long id) {
        return connectionRepository.findById(id)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));
    }

    public PaginatedResult<TableInfo> listTables(TableListQuery query) {
        DatasourceConnection connection = connectionRepository.findById(query.connectionId())
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));

        List<DatabaseSchema> schemas = databaseSchemaRepository.findByConnectionId(connection.id());
        if (schemas.isEmpty()) {
            return new PaginatedResult<>(List.of(), 0, query.page(), query.size());
        }

        DatabaseSchema schema = schemas.getFirst();
        List<TableInfo> tables = tableInfoRepository.findByDatabaseSchemaIdAndKeyword(
                schema.id(), query.keyword(), query.page(), query.size()
        );
        long total = tableInfoRepository.countByDatabaseSchemaIdAndKeyword(schema.id(), query.keyword());
        return new PaginatedResult<>(tables, total, query.page(), query.size());
    }
    
    public List<ApiSchema> listApiSchemas(Long connectionId) {
        return apiSchemaRepository.findByConnectionId(connectionId);
    }
    
    public List<ApiField> listApiFields(Long schemaId) {
        return apiFieldRepository.findByApiSchemaId(schemaId);
    }

    public List<ColumnInfo> listColumns(Long tableId) {
        return columnInfoRepository.findByTableId(tableId);
    }

    public void updateTableComment(Long tableId, String comment) {
        domainService.validateComment(comment);
        tableInfoRepository.updateTableCustomComment(tableId, comment);
    }

    public void updateColumnComment(Long columnId, String comment) {
        domainService.validateComment(comment);
        columnInfoRepository.updateColumnCustomComment(columnId, comment);
    }

    public List<Map<String, Object>> previewTableData(Long connectionId, String tableName, int limit) {
        DatasourceConnection connection = connectionRepository.findById(connectionId)
            .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));

        if (connection.status() != DatasourceStatus.ENABLED) {
            throw new DeepDataAgentException("数据源已禁用，请先启用数据源");
        }

        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);

        if (connection.type() == DatasourceType.API) {
            return strategy.previewData(connection, null, tableName, effectiveLimit);
        } else {
            List<DatabaseSchema> schemas = databaseSchemaRepository.findByConnectionId(connectionId);
            String schemaName = schemas.isEmpty() ? null : schemas.getFirst().schemaName();
            return strategy.previewData(connection, schemaName, tableName, effectiveLimit);
        }
    }

    private void doSyncMetadata(DatasourceConnection connection) {
        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());

        // API数据源元数据同步不在这里执行
        if (connection.type() == DatasourceType.JDBC) {
           syncJdbcMetadata(connection, strategy);
        }

    }

    private void syncJdbcMetadata(DatasourceConnection connection, DatasourceConnectionStrategy strategy) {
        int[] counters = new int[6];
        
        List<DatabaseSchema> remoteSchemas = strategy.extractSchemas(connection);
        List<DatabaseSchema> existingSchemas = databaseSchemaRepository.findByConnectionId(connection.id());

            for (DatabaseSchema remoteSchema : remoteSchemas) {
                DatabaseSchema localSchema = existingSchemas.stream()
                        .filter(s -> s.schemaName().equals(remoteSchema.schemaName()))
                        .findFirst()
                        .orElse(null);

                if (localSchema == null) {
                    localSchema = databaseSchemaRepository.save(new DatabaseSchema(
                            null, connection.id(), remoteSchema.schemaName(), null, null, null
                    ));
                    counters[0]++;
                }

                List<TableInfo> remoteTables = strategy.extractTables(connection, remoteSchema.schemaName());
                List<TableInfo> existingTables = tableInfoRepository.findByDatabaseSchemaId(localSchema.id());

                for (TableInfo remoteTable : remoteTables) {
                    var existingTableOpt = existingTables.stream()
                            .filter(t -> t.tableName().equals(remoteTable.tableName()))
                            .findFirst();

                    if (existingTableOpt.isEmpty()) {
                        TableInfo savedTable = tableInfoRepository.save(new TableInfo(
                                null, localSchema.id(), remoteTable.tableName(), remoteTable.tableComment(), null, null, null
                        ));
                        counters[1]++;

                        List<ColumnInfo> columns = strategy.extractColumns(connection, remoteSchema.schemaName(), remoteTable.tableName());
                        for (ColumnInfo column : columns) {
                            columnInfoRepository.save(new ColumnInfo(
                                    null, savedTable.id(), column.columnName(), column.dataType(), column.columnComment(), null, null, null
                            ));
                            counters[2]++;
                        }
                    } else {
                        TableInfo existingTable = existingTableOpt.get();
                        if (remoteTable.tableComment() != null && !remoteTable.tableComment().equals(existingTable.tableComment())) {
                            tableInfoRepository.updateTableCustomComment(existingTable.id(), remoteTable.tableComment());
                            counters[3]++;
                        }

                        List<ColumnInfo> remoteColumns = strategy.extractColumns(connection, remoteSchema.schemaName(), remoteTable.tableName());
                        List<ColumnInfo> existingColumns = columnInfoRepository.findByTableId(existingTable.id());

                        for (ColumnInfo remoteColumn : remoteColumns) {
                            var existingColumnOpt = existingColumns.stream()
                                    .filter(c -> c.columnName().equals(remoteColumn.columnName()))
                                    .findFirst();

                            if (existingColumnOpt.isEmpty()) {
                                columnInfoRepository.save(new ColumnInfo(
                                        null, existingTable.id(), remoteColumn.columnName(), remoteColumn.dataType(), remoteColumn.columnComment(), null, null, null
                                ));
                                counters[2]++;
                            }
                        }
                    }
                }

                for (TableInfo existingTable : existingTables) {
                    boolean stillExists = remoteTables.stream()
                            .anyMatch(t -> t.tableName().equals(existingTable.tableName()));
                    if (!stillExists) {
                        int existingColumnCount = existingColumnsCount(existingTable.id());
                        columnInfoRepository.softDeleteByTableId(existingTable.id());
                        tableInfoRepository.softDeleteById(existingTable.id());
                        counters[4]++;
                        counters[5] += existingColumnCount;
                    }
                }
            }

        log.info("JDBC元数据同步完成: 数据源={}, 新增schema={}, 新增表={}, 新增列={}, 更新表={}, 删除表={}, 删除列={}",
                connection.id(), counters[0], counters[1], counters[2], counters[3], counters[4], counters[5]);
    }

    private int existingColumnsCount(Long tableId) {
        return columnInfoRepository.findByTableId(tableId).size();
    }

    private boolean isConnectionConfigChanged(DatasourceConnection existing, DatasourceConnection updated) {
        if (existing.type() == DatasourceType.JDBC && updated.jdbcConnectionConfig() != null) {
            var oldConfig = existing.jdbcConnectionConfig();
            var newConfig = updated.jdbcConnectionConfig();
            return !oldConfig.host().equals(newConfig.host())
                    || oldConfig.port() != newConfig.port()
                    || !oldConfig.database().equals(newConfig.database())
                    || !oldConfig.username().equals(newConfig.username())
                    || !oldConfig.password().equals(newConfig.password());
        }
        if (existing.type() == DatasourceType.API && updated.apiConnectionConfig() != null) {
            var oldConfig = existing.apiConnectionConfig();
            var newConfig = updated.apiConnectionConfig();
            return !oldConfig.url().equals(newConfig.url());
        }
        return false;
    }

    private DatasourceConnection buildTempConnection(TestConnectionCommand command) {
        JdbcConnectionConfig jdbcConfig = null;
        ApiConnectionConfig apiConfig = null;
        ApiAuthConfig authConfig = null;

        DatasourceType type = DatasourceType.valueOf(command.type());
        JdbcType subType = command.subType() != null ? JdbcType.valueOf(command.subType()) : null;

        if (type == DatasourceType.JDBC) {
            jdbcConfig = new JdbcConnectionConfig(
                    command.host(), command.port() != null ? command.port() : 0,
                    command.database(), command.username(), command.password()
            );
        } else {
            apiConfig = new ApiConnectionConfig(
                    command.apiUrl(),
                    command.apiMethod() != null ? HttpMethod.valueOf(command.apiMethod()) : HttpMethod.GET,
                    null, null, null, null,
                    command.apiTimeout() != null ? command.apiTimeout() : 10,
                    command.apiJsonPath()
            );
            authConfig = new ApiAuthConfig(
                    command.apiAuthType() != null ? ApiAuthType.valueOf(command.apiAuthType()) : ApiAuthType.NO_AUTH,
                    command.apiAuthUsername(), command.apiAuthPassword(), command.apiAuthToken()
            );
        }

        return new DatasourceConnection(null, "temporary_test_connection", type, subType, DatasourceStatus.ENABLED,
                jdbcConfig, apiConfig, authConfig, null, null, null, null, null);
    }

    private void deleteRelatedMetadata(DatasourceConnection connection) {
        if (connection.type() == DatasourceType.JDBC) {
            List<DatabaseSchema> schemas = databaseSchemaRepository.findByConnectionId(connection.id());
            for (DatabaseSchema schema : schemas) {
                List<TableInfo> tables = tableInfoRepository.findByDatabaseSchemaId(schema.id());
                for (TableInfo table : tables) {
                    columnInfoRepository.softDeleteByTableId(table.id());
                }
                tableInfoRepository.softDeleteByDatabaseSchemaId(schema.id());
            }
            databaseSchemaRepository.softDeleteByConnectionId(connection.id());
            return;
        }

        if (connection.type() == DatasourceType.API) {
            List<ApiSchema> apiSchemas = apiSchemaRepository.findByConnectionId(connection.id());
            for (ApiSchema apiSchema : apiSchemas) {
                apiFieldRepository.deleteByApiSchemaId(apiSchema.id());
            }
            apiSchemaRepository.deleteByConnectionId(connection.id());
        }
    }

    /**
     * 解析API响应，提取字段列表
     * <p>接收前端传入的API请求配置，发送测试请求，解析响应JSON结构，
     * 自动提取字段列表供前端展示。</p>
     */
    public ParseApiResponseResult parseApiResponse(ParseApiResponseCommand command) {
        DatasourceConnection connection = buildConnectionForParse(command);

        ApiTableConfig tableConfig = new ApiTableConfig(
                command.apiUrl(),
                command.method() != null ? HttpMethod.valueOf(command.method()) : HttpMethod.GET,
                command.rootPath(),
                null
        );
        Map<String, Object> context = Map.of();

        String rawResponse = apiPaginationHandler.fetchRawResponse(connection, tableConfig, context);

        String rootPath = command.rootPath() != null ? command.rootPath() : "$";
        List<ApiField> apiFields = apiResponseParser.parseFields(rawResponse, rootPath);
        
        List<ParsedFieldResponse> fields = apiFields.stream()
                .map(field -> new ParsedFieldResponse(
                        field.originalName(),
                        field.displayName(),
                        field.fieldType(),
                        field.jsonPath()
                ))
                .toList();

        PaginatedApiResult result = apiPaginationHandler.executeOnce(connection, tableConfig, context);
        List<Map<String, Object>> previewData = result.data().stream()
                .limit(10)
                .toList();

        return new ParseApiResponseResult(fields, previewData);
    }

    /**
     * 构建用于解析的连接配置
     * <p>支持两种场景：
     * 1. 新建数据源：直接使用请求参数构建配置
     * 2. 已有数据源添加表：从数据库获取基础配置，叠加数据表级配置</p>
     */
    private DatasourceConnection buildConnectionForParse(ParseApiResponseCommand command) {
        if (command.connectionId() != null) {
            DatasourceConnection savedConnection = connectionRepository.findById(command.connectionId())
                    .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));
            
            if (savedConnection.type() != DatasourceType.API) {
                throw new DeepDataAgentException("仅支持API数据源");
            }
            
            ApiConnectionConfig existingConfig = savedConnection.apiConnectionConfig();
            String mergedUrl = command.apiUrl() != null ? command.apiUrl() : existingConfig.url();
            HttpMethod mergedMethod = command.method() != null ? HttpMethod.valueOf(command.method()) : existingConfig.method();
            Map<String, String> mergedHeaders = command.headers() != null ? command.headers() : existingConfig.headers();
            Map<String, String> mergedParams = command.params() != null ? command.params() : existingConfig.params();
            String mergedBody = command.body() != null ? command.body() : existingConfig.body();
            int mergedTimeout = command.timeout() != null ? command.timeout() : existingConfig.timeout();
            String mergedJsonPath = command.rootPath();

            ApiConnectionConfig mergedConfig = new ApiConnectionConfig(
                    mergedUrl,
                    mergedMethod,
                    mergedHeaders,
                    mergedParams,
                    mergedBody,
                    existingConfig.paginationConfig(),
                    mergedTimeout,
                    mergedJsonPath
            );
            
            return DatasourceConnection.restore(
                    savedConnection.id(), savedConnection.name(), savedConnection.type(), savedConnection.subType(),
                    savedConnection.status(), savedConnection.jdbcConnectionConfig(),
                    mergedConfig, savedConnection.apiAuthConfig(),
                    savedConnection.description(), savedConnection.createdAt(), savedConnection.updatedAt(),
                    savedConnection.createdBy(), savedConnection.updatedBy()
            );
        }

        int timeout = command.timeout() != null ? command.timeout() : 180;
        ApiConnectionConfig newConfig = new ApiConnectionConfig(
                command.apiUrl(),
                command.method() != null ? HttpMethod.valueOf(command.method()) : HttpMethod.GET,
                command.headers(),
                command.params(),
                command.body(),
                null,
                timeout,
                command.rootPath()
        );

        ApiAuthConfig authConfig = buildAuthConfig(command);

        return DatasourceConnection.create(
                "临时解析连接",
                DatasourceType.API,
                null,
                null,
                null,
                newConfig,
                authConfig
        );
    }

    /**
     * 构建认证配置
     */
    private ApiAuthConfig buildAuthConfig(ParseApiResponseCommand command) {
        if (command.authType() == null) {
            return new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null, null);
        }

        ApiAuthType authType = switch (command.authType().toLowerCase()) {
            case "bearer", "bearer_token" -> ApiAuthType.BEARER_TOKEN;
            case "basic", "basic_auth" -> ApiAuthType.BASIC_AUTH;
            default -> ApiAuthType.NO_AUTH;
        };

        return new ApiAuthConfig(
                authType,
                command.authToken(),
                command.authUsername(),
                command.authPassword()
        );
    }

    public record PaginatedResult<T>(List<T> data, long total, int page, int size) {
    }
}
