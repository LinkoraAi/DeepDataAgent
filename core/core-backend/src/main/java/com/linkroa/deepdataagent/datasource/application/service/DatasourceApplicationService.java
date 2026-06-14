package com.linkroa.deepdataagent.datasource.application.service;

import com.linkroa.deepdataagent.datasource.application.assembler.DatasourceAssembler;
import com.linkroa.deepdataagent.datasource.application.command.ApiSchemaCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.controller.request.*;
import com.linkroa.deepdataagent.datasource.controller.response.*;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.*;
import com.linkroa.deepdataagent.datasource.domain.repository.*;
import com.linkroa.deepdataagent.datasource.domain.service.DatasourceConnectionDomainService;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategy;
import com.linkroa.deepdataagent.datasource.domain.strategy.DatasourceConnectionStrategyFactory;
import com.linkroa.deepdataagent.datasource.infrastructure.adapter.ApiResponseParser;
import com.linkroa.deepdataagent.datasource.infrastructure.client.ApiConnectionStrategy;
import com.linkroa.deepdataagent.datasource.infrastructure.client.ApiPaginationHandler;
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

@Service
public class DatasourceApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceApplicationService.class);

    private final DatasourceConnectionRepository connectionRepository;
    private final DatasourceConnectionStrategyFactory strategyFactory;
    private final DatasourceConnectionDomainService domainService;
    private final TransactionTemplate transactionTemplate;
    private final DatabaseSchemaRepository databaseSchemaRepository;
    private final TableInfoRepository tableInfoRepository;
    private final ColumnInfoRepository columnInfoRepository;
    private final ApiSchemaRepository apiSchemaRepository;
    private final ApiFieldRepository apiFieldRepository;
    private final ApiResponseParser apiResponseParser;
    private final ApiPaginationHandler apiPaginationHandler;

    public DatasourceApplicationService(
            DatasourceConnectionRepository connectionRepository,
            DatasourceConnectionStrategyFactory strategyFactory,
            DatasourceConnectionDomainService domainService,
            TransactionTemplate transactionTemplate,
            DatabaseSchemaRepository databaseSchemaRepository,
            TableInfoRepository tableInfoRepository,
            ColumnInfoRepository columnInfoRepository,
            ApiSchemaRepository apiSchemaRepository,
            ApiFieldRepository apiFieldRepository,
            ApiResponseParser apiResponseParser,
            ApiPaginationHandler apiPaginationHandler
    ) {
        this.connectionRepository = connectionRepository;
        this.strategyFactory = strategyFactory;
        this.domainService = domainService;
        this.transactionTemplate = transactionTemplate;
        this.databaseSchemaRepository = databaseSchemaRepository;
        this.tableInfoRepository = tableInfoRepository;
        this.columnInfoRepository = columnInfoRepository;
        this.apiSchemaRepository = apiSchemaRepository;
        this.apiFieldRepository = apiFieldRepository;
        this.apiResponseParser = apiResponseParser;
        this.apiPaginationHandler = apiPaginationHandler;
    }

    public List<DatasourceTypeResponse> getSupportedTypes() {
        List<DatasourceTypeResponse> types = new ArrayList<>();
        for (DatasourceTypeEnum typeEnum : DatasourceTypeEnum.values()) {
            types.add(new DatasourceTypeResponse(
                    typeEnum.getType(), typeEnum.getSubType(),
                    typeEnum.getName(), typeEnum.getCategory()
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
            if (saved.type() == DatasourceType.API && ObjectUtils.isNotEmpty(command.apiSchemas())) {
                for (var schemaCommand : command.apiSchemas()) {
                    saveApiSchema(saved.id(), schemaCommand);
                }
            } else if (saved.type() == DatasourceType.JDBC) {
                doSyncMetadata(saved);
            }
            return saved;
        });
    }

    public DatasourceConnection updateDatasource(UpdateDatasourceCommand command) {
        DatasourceConnection existing = connectionRepository.findById(command.id())
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));
        if (command.name() != null && !command.name().equals(existing.name())) {
            connectionRepository.findByName(command.name())
                    .filter(c -> !c.id().equals(existing.id()))
                    .ifPresent(c -> { throw new DeepDataAgentException("数据源名称已被使用"); });
        }
        DatasourceConnection updated = DatasourceAssembler.toDatasourceConnection(command, existing);
        return transactionTemplate.execute(status -> connectionRepository.update(updated));
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
            if (connection.type() == DatasourceType.API) {
                deleteApiSchemasByConnectionId(connection.id());
            } else {
                deleteRelatedMetadata(connection);
            }
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
        JdbcType subType = type == DatasourceType.JDBC && StringUtils.isNotBlank(command.subType()) ? JdbcType.valueOf(command.subType()) : null;
        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(type, subType);
        if (type == DatasourceType.API) {
            ApiSchema tempSchema = buildTempApiSchema(command);
            return ((ApiConnectionStrategy) strategy).testConnection(tempSchema);
        }
        DatasourceConnection tempConnection = buildTempConnection(command);
        return strategy.testConnection(tempConnection);
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

    // ==================== Metadata Operations ====================

    public void syncMetadata(Long connectionId) {
        DatasourceConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));
        domainService.validateCanSync(connection);
        transactionTemplate.executeWithoutResult(status -> doSyncMetadata(connection));
    }

    public void doSyncMetadata(DatasourceConnection connection) {
        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());
        if (connection.type() == DatasourceType.JDBC) {
            syncJdbcMetadata(connection, strategy);
        }
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

    public void deleteRelatedMetadata(DatasourceConnection connection) {
        if (connection.type() == DatasourceType.JDBC) {
            deleteJdbcMetadata(connection);
        }
    }

    private void deleteJdbcMetadata(DatasourceConnection connection) {
        List<DatabaseSchema> schemas = databaseSchemaRepository.findByConnectionId(connection.id());
        for (DatabaseSchema schema : schemas) {
            List<TableInfo> tables = tableInfoRepository.findByDatabaseSchemaId(schema.id());
            for (TableInfo table : tables) {
                columnInfoRepository.softDeleteByTableId(table.id());
            }
            tableInfoRepository.softDeleteByDatabaseSchemaId(schema.id());
        }
        databaseSchemaRepository.softDeleteByConnectionId(connection.id());
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
                    int existingColumnCount = columnInfoRepository.findByTableId(existingTable.id()).size();
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

    // ==================== API Schema Operations ====================

    public ApiSchema createApiSchema(Long connectionId, ApiSchemaCommand schemaCommand) {
        DatasourceConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));
        if (connection.type() != DatasourceType.API) {
            throw new DeepDataAgentException("仅支持API类型数据源");
        }
        return transactionTemplate.execute(status -> saveApiSchema(connectionId, schemaCommand));
    }

    public ApiSchema saveApiSchema(Long connectionId, ApiSchemaCommand schemaCommand) {
        if (StringUtils.isBlank(schemaCommand.name())) {
            throw new DeepDataAgentException("API表名称不能为空");
        }
        if (StringUtils.isBlank(schemaCommand.url())) {
            throw new DeepDataAgentException("API请求地址不能为空");
        }

        ApiAuthConfig authConfig;
        if (schemaCommand.authType() != null && schemaCommand.authType() != ApiAuthType.NO_AUTH) {
            authConfig = new ApiAuthConfig(schemaCommand.authType(), schemaCommand.authUsername(), schemaCommand.authPassword());
        } else {
            authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null);
        }

        ApiPaginationConfig paginationConfig = null;
        if (StringUtils.isNotBlank(schemaCommand.paginationType())) {
            paginationConfig = new ApiPaginationConfig(
                    ApiPaginationType.valueOf(schemaCommand.paginationType()),
                    schemaCommand.pageNumberParamName(),
                    schemaCommand.pageSizeParamName(),
                    schemaCommand.totalCountJsonPath(),
                    schemaCommand.pageSize(),
                    schemaCommand.maxPages()
            );
        }

        BodyType bodyType = schemaCommand.bodyType() != null && !schemaCommand.bodyType().isBlank()
                ? BodyType.valueOf(schemaCommand.bodyType().toUpperCase()) : null;

        ApiRequestConfig requestConfig = new ApiRequestConfig(
                schemaCommand.headers(),
                schemaCommand.params(),
                schemaCommand.body(),
                bodyType,
                schemaCommand.jsonPathConfig(),
                schemaCommand.timeout() != null ? schemaCommand.timeout() : 180,
                schemaCommand.retryCount(),
                authConfig,
                paginationConfig,
                schemaCommand.preOperationConfigs()
        );

        ApiSchema schema = new ApiSchema(
            null, connectionId, schemaCommand.name(), schemaCommand.url(),
            schemaCommand.method() != null ? schemaCommand.method() : HttpMethod.GET,
            requestConfig, LocalDateTime.now(), LocalDateTime.now(), null, null
        );
        ApiSchema savedSchema = apiSchemaRepository.save(schema);

        if (schemaCommand.fields() != null && !schemaCommand.fields().isEmpty()) {
            for (var f : schemaCommand.fields()) {
                ApiField field = new ApiField(
                    null, savedSchema.id(), f.originalName(), f.displayName(),
                    f.jsonPath(), f.fieldType(), f.description(),
                    LocalDateTime.now(), LocalDateTime.now()
                );
                apiFieldRepository.save(field);
            }
        }
        return savedSchema;
    }

    public ApiSchema updateApiSchema(Long schemaId, UpdateApiSchemaRequest request) {
        ApiSchema existing = apiSchemaRepository.findById(schemaId)
                .orElseThrow(() -> new DeepDataAgentException("API表不存在"));

        ApiRequestConfig existingConfig = existing.config() != null ? existing.config() : ApiRequestConfig.defaultConfig();

        ApiAuthConfig authConfig = existingConfig.authConfig();
        if (request.authConfig() != null) {
            ApiAuthType authType = parseAuthTypeFromRequest(request.authConfig().authType());
            if (authType == ApiAuthType.BASIC_AUTH) {
                authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH,
                        request.authConfig().username(), request.authConfig().password());
            } else {
                authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null);
            }
        }

        ApiPaginationConfig paginationConfig = existingConfig.paginationConfig();
        if (request.paginationConfig() != null) {
            ApiPaginationType paginationType = request.paginationConfig().paginationType() != null
                    ? ApiPaginationType.valueOf(request.paginationConfig().paginationType()) : null;
            if (paginationType != null) {
                paginationConfig = new ApiPaginationConfig(
                        paginationType, request.paginationConfig().pageParamName(),
                        request.paginationConfig().sizeParamName(),
                        request.paginationConfig().totalCountJsonPath(),
                        request.paginationConfig().pageSize(), request.paginationConfig().maxPages()
                );
            } else {
                paginationConfig = null;
            }
        }

        List<PreOperationConfig> preOperationConfigs = request.preOperationConfigs() != null
                ? request.preOperationConfigs().stream().map(this::fromPreOpRequest).toList()
                : existingConfig.preOperationConfigs();

        String jsonPathConfig = request.jsonPathConfig() != null ? request.jsonPathConfig() : existingConfig.jsonPathConfig();
        Integer timeout = request.timeout() != null ? request.timeout() : existingConfig.timeout();
        Integer retryCount = request.retryCount() != null ? request.retryCount() : existingConfig.retryCount();

        Map<String, String> headers = request.headers() != null ? request.headers() : existingConfig.headers();
        Map<String, String> params = request.params() != null ? request.params() : existingConfig.params();
        String body = request.body() != null ? request.body() : existingConfig.body();
        BodyType bodyType = request.bodyType() != null && !request.bodyType().isBlank()
                ? BodyType.valueOf(request.bodyType().toUpperCase())
                : existingConfig.bodyType();

        ApiRequestConfig updatedConfig = new ApiRequestConfig(
                headers, params, body, bodyType, jsonPathConfig, timeout, retryCount,
                authConfig, paginationConfig, preOperationConfigs
        );

        String name = request.name() != null ? request.name() : existing.name();
        String url = request.url() != null ? request.url() : existing.url();
        HttpMethod method = request.method() != null && !request.method().isBlank()
                ? HttpMethod.valueOf(request.method().toUpperCase())
                : existing.method();

        ApiSchema updatedSchema = new ApiSchema(
                existing.id(), existing.connectionId(), name, url,
                method, updatedConfig, existing.createdAt(), LocalDateTime.now(),
                existing.createdBy(), existing.updatedBy()
        );

        return transactionTemplate.execute(status -> {
            ApiSchema saved = apiSchemaRepository.update(updatedSchema);
            if (request.fields() != null) {
                apiFieldRepository.deleteByApiSchemaId(schemaId);
                for (var f : request.fields()) {
                    ApiField field = new ApiField(
                            null, schemaId, f.originalName(), f.displayName(),
                            f.jsonPath(), f.fieldType(), f.description(),
                            LocalDateTime.now(), LocalDateTime.now()
                    );
                    apiFieldRepository.save(field);
                }
            }
            return saved;
        });
    }

    public void deleteApiSchema(Long schemaId) {
        apiSchemaRepository.findById(schemaId)
                .orElseThrow(() -> new DeepDataAgentException("API表不存在"));
        transactionTemplate.executeWithoutResult(status -> {
            apiFieldRepository.deleteByApiSchemaId(schemaId);
            apiSchemaRepository.deleteById(schemaId);
        });
    }

    public void deleteApiSchemasByConnectionId(Long connectionId) {
        List<ApiSchema> apiSchemas = apiSchemaRepository.findByConnectionId(connectionId);
        for (ApiSchema apiSchema : apiSchemas) {
            apiFieldRepository.deleteByApiSchemaId(apiSchema.id());
        }
        apiSchemaRepository.deleteByConnectionId(connectionId);
    }

    public ApiSchemaDetailResponse getApiSchemaDetail(Long schemaId) {
        ApiSchema schema = apiSchemaRepository.findById(schemaId)
                .orElseThrow(() -> new DeepDataAgentException("API表不存在"));
        List<ApiField> fields = apiFieldRepository.findByApiSchemaId(schemaId);
        return toApiSchemaDetailResponse(schema, fields);
    }

    public List<ApiSchema> listApiSchemas(Long connectionId) {
        return apiSchemaRepository.findByConnectionId(connectionId);
    }

    public List<ApiField> listApiFields(Long schemaId) {
        return apiFieldRepository.findByApiSchemaId(schemaId);
    }

    public Map<String, Object> testPreOperation(TestPreOperationRequest request) {
        PreOperationConfig preOpConfig = toPreOperationConfig(request);
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        if (request.authConfig() != null && request.authConfig().authType() != null) {
            ApiAuthConfig authConfig = new ApiAuthConfig(
                    ApiAuthType.fromRequestString(request.authConfig().authType()),
                    request.authConfig().username(), request.authConfig().password()
            );
            applyAuthToContext(context, authConfig);
        }

        ApiSchema tempSchema = new ApiSchema(null, null, null, preOpConfig.url(), preOpConfig.method(), null, null, null, null, null);
        ApiTableConfig tempTableConfig = new ApiTableConfig(
                preOpConfig.url(), preOpConfig.method(), preOpConfig.headers(),
                preOpConfig.params(), preOpConfig.body(), null, 180, null,
                List.of(preOpConfig), 0, preOpConfig.bodyType()
        );

        String rawResponse = apiPaginationHandler.fetchRawResponse(tempSchema, tempTableConfig, context);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("success", true);
        result.put("rawResponse", rawResponse);
        return result;
    }

    public ParseApiResponseResult parseApiResponse(ParseApiResponseCommand command) {
        ApiSchema apiSchema = buildApiSchemaForParse(command);
        Map<String, Object> context = Map.of();

        String rawResponse = apiPaginationHandler.fetchRawResponse(apiSchema, null, context);

        String rootPath = command.rootPath() != null ? command.rootPath() : "$";
        List<ParsedFieldResponse> fieldTree = apiResponseParser.parseFieldsAsTree(rawResponse, rootPath);

        PaginatedApiResult result = apiPaginationHandler.executeOnce(apiSchema, null, context);
        List<Map<String, Object>> rows = result.data().stream().limit(10).toList();

        return new ParseApiResponseResult(fieldTree, rows);
    }

    // ==================== Helper Methods ====================

    private DatasourceConnection buildTempConnection(TestConnectionCommand command) {
        DatasourceType type = DatasourceType.valueOf(command.type());
        JdbcType subType = type == DatasourceType.JDBC && command.subType() != null ? JdbcType.valueOf(command.subType()) : null;
        JdbcConnectionConfig jdbcConfig = null;
        if (type == DatasourceType.JDBC) {
            jdbcConfig = new JdbcConnectionConfig(
                    command.host(), command.port() != null ? command.port() : 0,
                    command.database(), command.username(), command.password()
            );
        }
        return DatasourceConnection.create("temporary_test_connection", type, subType, null, jdbcConfig);
    }

    private ApiSchema buildTempApiSchema(TestConnectionCommand command) {
        ApiAuthConfig authConfig = new ApiAuthConfig(
                command.apiAuthType() != null ? ApiAuthType.valueOf(command.apiAuthType()) : ApiAuthType.NO_AUTH,
                command.apiAuthUsername(), command.apiAuthPassword()
        );
        BodyType bodyType = command.apiBodyType() != null && !command.apiBodyType().isBlank()
                ? BodyType.valueOf(command.apiBodyType().toUpperCase()) : null;
        ApiRequestConfig requestConfig = new ApiRequestConfig(
                command.apiHeaders(), command.apiParams(), command.apiBody(), bodyType,
                command.apiJsonPath(),
                command.apiTimeout() != null ? command.apiTimeout() : 180,
                null, authConfig, null, null
        );
        return new ApiSchema(
                null, null, "temp_test", command.apiUrl(),
                command.apiMethod() != null ? HttpMethod.valueOf(command.apiMethod()) : HttpMethod.GET,
                requestConfig, null, null, null, null
        );
    }

    private void applyAuthToContext(Map<String, Object> context, ApiAuthConfig authConfig) {
        if (authConfig.authType() == ApiAuthType.BASIC_AUTH) {
            String credentials = authConfig.username() + ":" + authConfig.password();
            String encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
            context.put("Authorization", "Basic " + encodedCredentials);
        }
    }

    private PreOperationConfig fromPreOpRequest(PreOperationConfigRequest request) {
        return new PreOperationConfig(
                request.enabled() != null ? request.enabled() : false,
                request.url(),
                request.method() != null ? HttpMethod.valueOf(request.method().toUpperCase()) : HttpMethod.GET,
                request.headers(), request.params(), request.body(),
                request.bodyType() != null && !request.bodyType().isBlank() ? BodyType.valueOf(request.bodyType().toUpperCase()) : null,
                request.paramMappings() != null ? request.paramMappings().stream()
                        .map(m -> new ParamMapping(m.paramName(), m.paramLocation(), m.jsonPath()))
                        .toList() : List.of()
        );
    }

    private PreOperationConfig toPreOperationConfig(TestPreOperationRequest request) {
        return new PreOperationConfig(
                true, request.url(),
                request.method() != null ? HttpMethod.valueOf(request.method().toUpperCase()) : HttpMethod.GET,
                request.headers(), request.params(), request.body(),
                request.bodyType() != null && !request.bodyType().isBlank() ? BodyType.valueOf(request.bodyType().toUpperCase()) : null,
                List.of()
        );
    }

    private ApiSchema buildApiSchemaForParse(ParseApiResponseCommand command) {
        if (command.connectionId() != null) {
            DatasourceConnection savedConnection = connectionRepository.findById(command.connectionId())
                    .orElseThrow(() -> new DeepDataAgentException("数据源不存在"));
            if (savedConnection.type() != DatasourceType.API) {
                throw new DeepDataAgentException("仅支持API数据源");
            }
            List<ApiSchema> existingSchemas = apiSchemaRepository.findByConnectionId(command.connectionId());
            if (!existingSchemas.isEmpty()) {
                ApiSchema baseSchema = existingSchemas.getFirst();
                ApiRequestConfig baseConfig = baseSchema.config() != null ? baseSchema.config() : ApiRequestConfig.defaultConfig();
                String url = command.url() != null ? command.url() : baseSchema.url();
                HttpMethod method = command.method() != null ? HttpMethod.valueOf(command.method()) : baseSchema.method();
                Map<String, String> headers = command.headers() != null ? command.headers() : baseConfig.headers();
                Map<String, String> params = command.params() != null ? command.params() : baseConfig.params();
                String body = command.body() != null ? command.body() : baseConfig.body();
                int timeout = command.timeout() != null ? command.timeout() : baseConfig.timeout();
                String jsonPathConfig = command.rootPath() != null ? command.rootPath() : baseConfig.jsonPathConfig();
                BodyType bodyType = command.bodyType() != null ? BodyType.valueOf(command.bodyType().toUpperCase()) : baseConfig.bodyType();
                ApiAuthConfig authConfig = baseConfig.authConfig();
                ApiPaginationConfig paginationConfig = command.paginationType() != null
                        ? buildPaginationConfig(command) : baseConfig.paginationConfig();
                List<PreOperationConfig> preOperationConfigs = command.preOperationConfigs() != null
                        ? command.preOperationConfigs() : baseConfig.preOperationConfigs();
                ApiRequestConfig mergedConfig = new ApiRequestConfig(
                        headers, params, body, bodyType, jsonPathConfig, timeout,
                        baseConfig.retryCount(), authConfig, paginationConfig, preOperationConfigs
                );
                return new ApiSchema(
                        baseSchema.id(), baseSchema.connectionId(), baseSchema.name(),
                        url, method, mergedConfig,
                        baseSchema.createdAt(), baseSchema.updatedAt(), baseSchema.createdBy(), baseSchema.updatedBy()
                );
            }
        }

        int timeout = command.timeout() != null ? command.timeout() : 180;
        BodyType bodyType = command.bodyType() != null ? BodyType.valueOf(command.bodyType().toUpperCase()) : BodyType.JSON;
        ApiAuthConfig authConfig = buildAuthConfig(command);
        ApiPaginationConfig paginationConfig = command.paginationType() != null
                ? buildPaginationConfig(command) : null;
        List<PreOperationConfig> preOperationConfigs = command.preOperationConfigs();
        ApiRequestConfig requestConfig = new ApiRequestConfig(
                command.headers(), command.params(), command.body(), bodyType,
                command.rootPath(), timeout, command.retryCount(), authConfig,
                paginationConfig, preOperationConfigs
        );
        return new ApiSchema(
                null, command.connectionId(), "temp_parse", command.url(),
                command.method() != null ? HttpMethod.valueOf(command.method()) : HttpMethod.GET,
                requestConfig, null, null, null, null
        );
    }

    private ApiPaginationConfig buildPaginationConfig(ParseApiResponseCommand command) {
        ApiPaginationType paginationType = ApiPaginationType.valueOf(command.paginationType());
        return new ApiPaginationConfig(
                paginationType, command.pageParamName(), command.sizeParamName(),
                command.totalCountJsonPath(), command.pageSize(), command.maxPages()
        );
    }

    private ApiAuthConfig buildAuthConfig(ParseApiResponseCommand command) {
        if (command.authType() == null) {
            return new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null);
        }
        ApiAuthType authType = parseAuthTypeFromRequest(command.authType());
        return new ApiAuthConfig(authType, command.authUsername(), command.authPassword());
    }

    private ApiAuthType parseAuthTypeFromRequest(String authType) {
        if (authType == null) return ApiAuthType.NO_AUTH;
        return ApiAuthType.fromRequestString(authType);
    }

    private ApiSchemaDetailResponse toApiSchemaDetailResponse(ApiSchema schema, List<ApiField> fields) {
        ApiRequestConfig config = schema.config() != null ? schema.config() : ApiRequestConfig.defaultConfig();
        return new ApiSchemaDetailResponse(
                schema.id(), schema.connectionId(), schema.name(), schema.url(),
                schema.method() != null ? schema.method().name() : null,
                config.headers(), config.params(), config.body(),
                config.bodyType() != null ? config.bodyType().name() : null,
                config.jsonPathConfig(), config.timeout(), config.retryCount(),
                config.authConfig() != null ? new ApiAuthConfigResponse(config.authConfig().authType().name(), config.authConfig().username()) : null,
                config.paginationConfig() != null ? new ApiPaginationConfigResponse(
                        config.paginationConfig().paginationType() != null ? config.paginationConfig().paginationType().name() : null,
                        config.paginationConfig().pageParamName(), config.paginationConfig().sizeParamName(),
                        config.paginationConfig().totalCountJsonPath(), config.paginationConfig().pageSize(),
                        config.paginationConfig().maxPages()
                ) : null,
                config.preOperationConfigs() != null ? config.preOperationConfigs().stream().map(p -> new PreOperationConfigResponse(
                        p.enabled(), p.url(), p.method() != null ? p.method().name() : null,
                        p.headers(), p.params(), p.body(),
                        p.bodyType() != null ? p.bodyType().name() : null,
                        p.paramMappings() != null ? p.paramMappings().stream().map(m -> new ParamMappingResponse(m.paramName(), m.paramLocation(), m.jsonPath())).toList() : null
                )).toList() : null,
                fields.stream().map(f -> new ApiFieldResponse(f.id(), f.apiSchemaId(), f.originalName(), f.displayName(), f.jsonPath(), f.fieldType(), f.description())).toList(),
                schema.createdAt(), schema.updatedAt()
        );
    }

    public record PaginatedResult<T>(List<T> data, long total, int page, int size) {
    }
}
