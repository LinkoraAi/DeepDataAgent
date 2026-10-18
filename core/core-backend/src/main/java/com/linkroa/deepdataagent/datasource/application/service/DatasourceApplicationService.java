package com.linkroa.deepdataagent.datasource.application.service;

import com.linkroa.deepdataagent.datasource.application.command.ApiSchemaCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.convert.DatasourceConvert;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.application.validation.DatasourceValidator;
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
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 数据源应用服务（用例编排：连接管理 / 元数据同步 / API 表配置 / 响应解析）。
 * <p>对外经 {@code /api/v1/datasource/*} 版本化管理面暴露（见 {@code datasource.controller}）；
 * 方法签名只使用命令 / 查询 / 领域模型与应用层读模型，凭证仍经加密存储、
 * 由协议层响应 DTO 统一脱敏，不随任何对外契约泄露明文。</p>
 * <p>错误语义：资源不存在抛 {@link ResourceNotFoundException}（404）、
 * 名称冲突与状态非法操作抛 {@link ResourceConflictException}（409）、
 * 参数与格式校验抛 {@link DeepDataAgentException} / {@link IllegalArgumentException}（400）。</p>
 */
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

    /**
     * 支持的数据源类型值（领域枚举全集；API 面收敛后作为内部能力输出，不再包装协议 DTO）。
     */
    public List<DatasourceTypeEnum> getSupportedTypes() {
        return List.of(DatasourceTypeEnum.values());
    }

    public DatasourceConnection createDatasource(CreateDatasourceCommand command) {
        // 归属即当前认证用户：名称判重与后续落库均在该归属者范围内进行
        Long ownerId = AuthContext.requireUserId();
        if (connectionRepository.findByNameAndOwner(command.name(), ownerId).isPresent()) {
            throw new ResourceConflictException("数据源名称已被使用");
        }
        if (command.type() == DatasourceType.JDBC && command.jdbcConfig() != null) {
            DatasourceValidator.validatePostgresqlSchema(command.subType(), command.jdbcConfig().schema());
        }
        DatasourceConnection connection = DatasourceConvert.INSTANCE.toDatasourceConnection(command, ownerId);
        DatasourceConnection saved = transactionTemplate.execute(status -> {
            DatasourceConnection persisted = connectionRepository.save(connection);
            if (persisted.type() == DatasourceType.API && ObjectUtils.isNotEmpty(command.apiSchemas())) {
                for (var schemaCommand : command.apiSchemas()) {
                    saveApiSchema(persisted.id(), schemaCommand);
                }
            }
            return persisted;
        });
        // JDBC 元数据同步置于事务外：连接行先提交，远程元数据抽取是可重入增量对账，
        // 同步失败不回滚连接行（下次更新或手动 syncMetadata 补齐），与 updateDatasource 口径统一，
        // 也避免远程 JDBC 调用长事务占用本地数据库连接
        if (saved.type() == DatasourceType.JDBC) {
            doSyncMetadata(saved);
        }
        return saved;
    }

    public DatasourceConnection updateDatasource(UpdateDatasourceCommand command) {
        DatasourceConnection existing = requireOwnedConnection(command.id());
        if (command.name() != null && !command.name().equals(existing.name())) {
            // 判重范围 = 归属者内（uk_name 已收敛为 (owner_id, name)）
            connectionRepository.findByNameAndOwner(command.name(), existing.ownerId())
                    .filter(c -> !c.id().equals(existing.id()))
                    .ifPresent(c -> { throw new ResourceConflictException("数据源名称已被使用"); });
        }
        if (command.jdbcConfig() != null) {
            DatasourceValidator.validatePostgresqlSchema(existing.subType(), command.jdbcConfig().schema());
        }
        DatasourceConnection updated = DatasourceConvert.INSTANCE.toDatasourceConnection(command, existing);
        DatasourceConnection saved = transactionTemplate.execute(status -> connectionRepository.update(updated));
        // JDBC 数据源更新成功后重新同步元数据（置于事务外，避免远程调用占用数据库连接）
        if (saved.type() == DatasourceType.JDBC) {
            doSyncMetadata(saved);
        }
        return saved;
    }

    public void enableDatasource(Long id) {
        DatasourceConnection connection = requireOwnedConnection(id);
        domainService.validateCanEnable(connection);
        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());
        DatasourceConnectionStrategy.ConnectionTestResult result = strategy.testConnection(connection);
        if (!result.success()) {
            throw new DeepDataAgentException("连接测试失败: " + result.message());
        }
        transactionTemplate.executeWithoutResult(status -> connectionRepository.updateStatus(id, DatasourceStatus.ENABLED));
    }

    public void disableDatasource(Long id) {
        DatasourceConnection connection = requireOwnedConnection(id);
        domainService.validateCanDisable(connection);
        transactionTemplate.executeWithoutResult(status -> connectionRepository.updateStatus(id, DatasourceStatus.DISABLED));
    }

    public void deleteDatasource(Long id) {
        DatasourceConnection connection = requireOwnedConnection(id);
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
            // 按标识测试已存连接：归属前置，非本人归属 404 且 MUST NOT 建立任何远端连接
            DatasourceConnection connection = requireOwnedConnection(command.id());
            DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());
            return strategy.testConnection(connection);
        }
        DatasourceType type = DatasourceType.valueOf(command.type());
        JdbcType subType = type == DatasourceType.JDBC && StringUtils.isNotBlank(command.subType()) ? JdbcType.valueOf(command.subType()) : null;
        DatasourceValidator.validatePostgresqlSchema(subType, command.schema());
        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(type, subType);
        if (type == DatasourceType.API) {
            ApiSchema tempSchema = buildTempApiSchema(command);
            return ((ApiConnectionStrategy) strategy).testConnection(tempSchema);
        }
        DatasourceConnection tempConnection = buildTempConnection(command);
        return strategy.testConnection(tempConnection);
    }

    public PaginatedResult<DatasourceConnection> listDatasources(ListDatasourceQuery query) {
        // 归属过滤：列表仅含当前认证用户的数据源
        Long ownerId = AuthContext.requireUserId();
        List<DatasourceConnection> connections = connectionRepository.findByCondition(
                query.keyword(), query.type(), query.status(), query.page(), query.size(), ownerId
        );
        long total = connectionRepository.countByCondition(query.keyword(), query.type(), query.status(), ownerId);
        return new PaginatedResult<>(connections, total, query.page(), query.size());
    }

    public DatasourceConnection getDatasource(Long id) {
        return requireOwnedConnection(id);
    }

    // ==================== Metadata Operations ====================

    public void syncMetadata(Long connectionId) {
        DatasourceConnection connection = requireOwnedConnection(connectionId);
        domainService.validateCanSync(connection);
        // 同步为可重入增量对账（schema/表/列逐条 upsert 与软删各自落库）：不包整体事务，
        // 远程元数据抽取不占用本地数据库连接；中途失败时已对账部分保留，下次同步补齐
        doSyncMetadata(connection);
    }

    public void doSyncMetadata(DatasourceConnection connection) {
        DatasourceConnectionStrategy strategy = strategyFactory.getStrategy(connection.type(), connection.subType());
        if (connection.type() == DatasourceType.JDBC) {
            syncJdbcMetadata(connection, strategy);
        }
    }

    public PaginatedResult<TableInfo> listTables(TableListQuery query) {
        DatasourceConnection connection = requireOwnedConnection(query.connectionId());

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
        // 归属前置：沿表标识父链解析所属数据源归属，非本人归属 404 折叠
        requireOwnedConnectionByTableId(tableId);
        return columnInfoRepository.findByTableId(tableId);
    }

    public void updateTableComment(Long tableId, String comment) {
        requireOwnedConnectionByTableId(tableId);
        domainService.validateComment(comment);
        tableInfoRepository.updateTableCustomComment(tableId, comment);
    }

    public void updateColumnComment(Long columnId, String comment) {
        requireOwnedConnectionByColumnId(columnId);
        domainService.validateComment(comment);
        columnInfoRepository.updateColumnCustomComment(columnId, comment);
    }

    public List<Map<String, Object>> previewTableData(Long connectionId, String tableName, int limit) {
        // 归属前置：非本人归属 404，MUST NOT 建立到目标库/API 的任何连接
        DatasourceConnection connection = requireOwnedConnection(connectionId);

        if (connection.status() != DatasourceStatus.ENABLED) {
            throw new ResourceConflictException("数据源已禁用，请先启用数据源");
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
        DatasourceConnection connection = requireOwnedConnection(connectionId);
        if (connection.type() != DatasourceType.API) {
            throw new ResourceConflictException("仅支持API类型数据源");
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
            requestConfig, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null
        );
        ApiSchema savedSchema = apiSchemaRepository.save(schema);

        if (schemaCommand.fields() != null && !schemaCommand.fields().isEmpty()) {
            for (var f : schemaCommand.fields()) {
                ApiField field = new ApiField(
                    null, savedSchema.id(), f.originalName(), f.displayName(),
                    f.jsonPath(), f.fieldType(), f.description(),
                    OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))
                );
                apiFieldRepository.save(field);
            }
        }
        return savedSchema;
    }

    /**
     * 更新 API 表配置（增量合并：命令字段 null=沿用现有）。
     * <p>鉴权：{@code authType} 非空即整体替换（BASIC 携带凭证，其余回落无鉴权）；
     * 分页：{@code paginationType} 非空即替换（空串=清除分页配置）；
     * 字段列表：非空即全量重建。</p>
     */
    public ApiSchema updateApiSchema(Long schemaId, ApiSchemaCommand command) {
        // 归属前置：沿 api_schema → datasource_connection 父链解析归属，非本人 404 折叠
        requireOwnedConnectionByApiSchemaId(schemaId);
        ApiSchema existing = apiSchemaRepository.findById(schemaId)
                .orElseThrow(() -> new ResourceNotFoundException("API表不存在"));

        ApiRequestConfig existingConfig = existing.config() != null ? existing.config() : ApiRequestConfig.defaultConfig();

        ApiAuthConfig authConfig = existingConfig.authConfig();
        if (command.authType() != null) {
            if (command.authType() == ApiAuthType.BASIC_AUTH) {
                authConfig = new ApiAuthConfig(ApiAuthType.BASIC_AUTH,
                        command.authUsername(), command.authPassword());
            } else {
                authConfig = new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null);
            }
        }

        ApiPaginationConfig paginationConfig = existingConfig.paginationConfig();
        if (command.paginationType() != null) {
            paginationConfig = command.paginationType().isBlank()
                    ? null
                    : new ApiPaginationConfig(
                            ApiPaginationType.valueOf(command.paginationType()),
                            command.pageNumberParamName(), command.pageSizeParamName(),
                            command.totalCountJsonPath(), command.pageSize(), command.maxPages());
        }

        List<PreOperationConfig> preOperationConfigs = command.preOperationConfigs() != null
                ? command.preOperationConfigs()
                : existingConfig.preOperationConfigs();

        String jsonPathConfig = command.jsonPathConfig() != null ? command.jsonPathConfig() : existingConfig.jsonPathConfig();
        Integer timeout = command.timeout() != null ? command.timeout() : existingConfig.timeout();
        Integer retryCount = command.retryCount() != null ? command.retryCount() : existingConfig.retryCount();

        Map<String, String> headers = command.headers() != null ? command.headers() : existingConfig.headers();
        Map<String, String> params = command.params() != null ? command.params() : existingConfig.params();
        String body = command.body() != null ? command.body() : existingConfig.body();
        BodyType bodyType = command.bodyType() != null && !command.bodyType().isBlank()
                ? BodyType.valueOf(command.bodyType().toUpperCase())
                : existingConfig.bodyType();

        ApiRequestConfig updatedConfig = new ApiRequestConfig(
                headers, params, body, bodyType, jsonPathConfig, timeout, retryCount,
                authConfig, paginationConfig, preOperationConfigs
        );

        String name = command.name() != null ? command.name() : existing.name();
        String url = command.url() != null ? command.url() : existing.url();
        HttpMethod method = command.method() != null ? command.method() : existing.method();

        ApiSchema updatedSchema = new ApiSchema(
                existing.id(), existing.connectionId(), name, url,
                method, updatedConfig, existing.createdAt(), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                existing.createdBy(), existing.updatedBy()
        );

        return transactionTemplate.execute(status -> {
            ApiSchema saved = apiSchemaRepository.update(updatedSchema);
            if (command.fields() != null) {
                apiFieldRepository.deleteByApiSchemaId(schemaId);
                for (var f : command.fields()) {
                    ApiField field = new ApiField(
                            null, schemaId, f.originalName(), f.displayName(),
                            f.jsonPath(), f.fieldType(), f.description(),
                            OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))
                    );
                    apiFieldRepository.save(field);
                }
            }
            return saved;
        });
    }

    public void deleteApiSchema(Long schemaId) {
        requireOwnedConnectionByApiSchemaId(schemaId);
        apiSchemaRepository.findById(schemaId)
                .orElseThrow(() -> new ResourceNotFoundException("API表不存在"));
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

    /**
     * 读取 API 表详情（schema + 字段列表组合读模型；API 面收敛后不再包装协议 Response）。
     */
    public ApiSchemaDetail getApiSchemaDetail(Long schemaId) {
        requireOwnedConnectionByApiSchemaId(schemaId);
        ApiSchema schema = apiSchemaRepository.findById(schemaId)
                .orElseThrow(() -> new ResourceNotFoundException("API表不存在"));
        List<ApiField> fields = apiFieldRepository.findByApiSchemaId(schemaId);
        return new ApiSchemaDetail(schema, fields);
    }

    public List<ApiSchema> listApiSchemas(Long connectionId) {
        requireOwnedConnection(connectionId);
        return apiSchemaRepository.findByConnectionId(connectionId);
    }

    public List<ApiField> listApiFields(Long schemaId) {
        requireOwnedConnectionByApiSchemaId(schemaId);
        return apiFieldRepository.findByApiSchemaId(schemaId);
    }

    /**
     * 试跑前置操作（换 token 等）：以领域配置直发临时请求，返回原始响应供配置调试。
     *
     * @param preOpConfig 前置操作领域配置（enabled 须为 true，URL/方法不变量由值对象校验）
     * @param authConfig  试跑携带的鉴权配置（null / 无鉴权类型则不带 Authorization 上下文）
     */
    public Map<String, Object> testPreOperation(PreOperationConfig preOpConfig, ApiAuthConfig authConfig) {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        if (authConfig != null && authConfig.authType() != null) {
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

    /**
     * 试解析 API 响应：返回字段树与扁平化样例行（各内部消费方共享的解析能力）。
     */
    public ParsedApiResponse parseApiResponse(ParseApiResponseCommand command) {
        ApiSchema apiSchema = buildApiSchemaForParse(command);
        Map<String, Object> context = Map.of();

        String rawResponse = apiPaginationHandler.fetchRawResponse(apiSchema, null, context);

        String rootPath = command.rootPath() != null ? command.rootPath() : "$";
        List<ParsedField> fieldTree = apiResponseParser.parseFieldsAsTree(rawResponse, rootPath);

        PaginatedApiResult result = apiPaginationHandler.executeOnce(apiSchema, null, context);
        List<Map<String, Object>> rows = result.data().stream().limit(10).toList();

        return new ParsedApiResponse(fieldTree, rows);
    }

    // ==================== Helper Methods ====================

    /**
     * 归属前置：读取数据源并校验归属当前认证用户（请求面统一入口）。
     * <p>不存在与非本人归属统一以 404 折叠（不泄露存在性）。</p>
     *
     * @param id 数据源主键
     * @return 归属当前用户的数据源
     * @throws ResourceNotFoundException 数据源不存在或非当前用户归属（404，两情形不可区分）
     */
    private DatasourceConnection requireOwnedConnection(Long id) {
        return connectionRepository.findByIdAndOwner(id, AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("数据源不存在"));
    }

    /**
     * 归属前置：沿父链（table_info → database_schema → datasource_connection）
     * 校验表所属数据源归属当前认证用户。
     *
     * @param tableId 表主键
     * @throws ResourceNotFoundException 表不存在或非当前用户归属（404，两情形不可区分）
     */
    private void requireOwnedConnectionByTableId(Long tableId) {
        requireSameOwner(tableInfoRepository.findOwnerIdByTableId(tableId), "表不存在");
    }

    /**
     * 归属前置：沿父链（column_info → table_info → database_schema → datasource_connection）
     * 校验列所属数据源归属当前认证用户。
     *
     * @param columnId 列主键
     * @throws ResourceNotFoundException 列不存在或非当前用户归属（404，两情形不可区分）
     */
    private void requireOwnedConnectionByColumnId(Long columnId) {
        requireSameOwner(columnInfoRepository.findOwnerIdByColumnId(columnId), "列不存在");
    }

    /**
     * 归属前置：沿父链（api_schema → datasource_connection）校验 API 数据表归属当前认证用户。
     *
     * @param schemaId API schema 主键
     * @throws ResourceNotFoundException 记录不存在或非当前用户归属（404，两情形不可区分）
     */
    private void requireOwnedConnectionByApiSchemaId(Long schemaId) {
        requireSameOwner(apiSchemaRepository.findOwnerIdByApiSchemaId(schemaId), "API表不存在");
    }

    /**
     * 父链归属判定收口：解析结果为空（父链断裂）或与当前用户不一致均以 404 折叠。
     */
    private void requireSameOwner(java.util.Optional<Long> resolvedOwnerId, String notFoundMessage) {
        Long currentOwnerId = AuthContext.requireUserId();
        if (resolvedOwnerId.isEmpty() || !currentOwnerId.equals(resolvedOwnerId.get())) {
            throw new ResourceNotFoundException(notFoundMessage);
        }
    }

    private DatasourceConnection buildTempConnection(TestConnectionCommand command) {
        DatasourceType type = DatasourceType.valueOf(command.type());
        JdbcType subType = type == DatasourceType.JDBC && command.subType() != null ? JdbcType.valueOf(command.subType()) : null;
        JdbcConnectionConfig jdbcConfig = null;
        if (type == DatasourceType.JDBC) {
            jdbcConfig = new JdbcConnectionConfig(
                    command.host(), command.port() != null ? command.port() : 0,
                    command.database(), command.username(), command.password(), command.schema()
            );
        }
        // 临时连接仅用于即时连通性测试、MUST NOT 落库；归属取当前请求者仅满足领域不变量
        return DatasourceConnection.create("temporary_test_connection", type, subType, null, jdbcConfig,
                AuthContext.requireUserId());
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

    private ApiSchema buildApiSchemaForParse(ParseApiResponseCommand command) {
        if (command.connectionId() != null) {
            // 归属前置：按已存连接解析时校验归属，非本人归属 404 折叠
            DatasourceConnection savedConnection = requireOwnedConnection(command.connectionId());
            if (savedConnection.type() != DatasourceType.API) {
                throw new ResourceConflictException("仅支持API数据源");
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

    public record PaginatedResult<T>(List<T> data, long total, int page, int size) {
    }

    /**
     * API 表详情读模型（schema 与字段列表组合；API 面收敛后的内部用例输出形状）。
     */
    public record ApiSchemaDetail(ApiSchema schema, List<ApiField> fields) {
    }

    /**
     * API 响应试解析结果读模型（字段树 + 扁平化样例行）。
     */
    public record ParsedApiResponse(List<ParsedField> fieldTree, List<Map<String, Object>> rows) {
    }
}
