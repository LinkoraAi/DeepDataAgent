package com.linkroa.deepdataagent.datasource.controller.convert;

import com.linkroa.deepdataagent.datasource.application.command.ApiFieldCommand;
import com.linkroa.deepdataagent.datasource.application.command.ApiSchemaCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.JdbcConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.controller.request.ApiFieldRequest;
import com.linkroa.deepdataagent.datasource.controller.request.ApiSchemaRequest;
import com.linkroa.deepdataagent.datasource.controller.request.CreateDatasourceRequest;
import com.linkroa.deepdataagent.datasource.controller.request.JdbcConfigRequest;
import com.linkroa.deepdataagent.datasource.controller.request.ParamMappingRequest;
import com.linkroa.deepdataagent.datasource.controller.request.ParseApiResponseRequest;
import com.linkroa.deepdataagent.datasource.controller.request.PreOperationConfigRequest;
import com.linkroa.deepdataagent.datasource.controller.request.PreOperationTestRequest;
import com.linkroa.deepdataagent.datasource.controller.request.TestConnectionRequest;
import com.linkroa.deepdataagent.datasource.controller.request.UpdateDatasourceRequest;
import com.linkroa.deepdataagent.datasource.controller.response.ApiAuthResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ApiFieldResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ApiSchemaResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ColumnInfoResponse;
import com.linkroa.deepdataagent.datasource.controller.response.DatasourceResponse;
import com.linkroa.deepdataagent.datasource.controller.response.DatasourceTypeResponse;
import com.linkroa.deepdataagent.datasource.controller.response.JdbcConfigResponse;
import com.linkroa.deepdataagent.datasource.controller.response.ParsedFieldResponse;
import com.linkroa.deepdataagent.datasource.controller.response.TableInfoResponse;
import com.linkroa.deepdataagent.datasource.domain.model.ApiAuthConfig;
import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import com.linkroa.deepdataagent.datasource.domain.model.ApiPaginationConfig;
import com.linkroa.deepdataagent.datasource.domain.model.ApiRequestConfig;
import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.JdbcConnectionConfig;
import com.linkroa.deepdataagent.datasource.domain.model.ParamMapping;
import com.linkroa.deepdataagent.datasource.domain.model.ParsedField;
import com.linkroa.deepdataagent.datasource.domain.model.PreOperationConfig;
import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiPaginationType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceTypeEnum;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import com.linkroa.deepdataagent.datasource.domain.model.enums.JdbcType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源 web 层转换器：请求 → 命令、领域对象 → 响应。
 *
 * <p>沿用项目既有 MapStruct 写法（{@code @Mapper} 接口 + 单例 {@code INSTANCE} + 全手写 default 方法），
 * 不依赖自动隐射：record 按位置构造、枚举字符串在此统一归一。</p>
 *
 * <p><b>脱敏约定</b>：JDBC 密码在任何响应中都不映射（{@link JdbcConfigResponse} 无 password 字段）；
 * API 鉴权密码映射为 {@link ApiAuthResponse#credentialConfigured()} 布尔标志，不回显明文或密文。</p>
 *
 * <p><b>枚举归一</b>：入参统一 trim + 转大写后 {@code valueOf}，无法识别抛 {@link IllegalArgumentException}（→400）；
 * {@link ApiAuthType} 沿用其 {@code fromRequestString} 容错（{@code basic} / {@code basic_auth} → BASIC_AUTH）。</p>
 *
 * @author DeepDataAgent
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DatasourceWebConvert {

    /** 转换器单例（与项目既有 convert 口径一致，非 Spring 注入）。 */
    DatasourceWebConvert INSTANCE = Mappers.getMapper(DatasourceWebConvert.class);

    // ==================== 请求 → 命令 ====================

    /**
     * 创建数据源请求 → 命令。
     *
     * @param request 请求体
     * @return 创建命令
     */
    default CreateDatasourceCommand toCreateCommand(CreateDatasourceRequest request) {
        DatasourceType type = parseDatasourceType(request.type());
        JdbcType subType = type == DatasourceType.JDBC ? parseJdbcType(request.subType()) : null;
        JdbcConfigCommand jdbcConfig = toJdbcConfigCommand(request.jdbcConfig());
        List<ApiSchemaCommand> apiSchemas = request.apiSchemas() == null
                ? null
                : request.apiSchemas().stream().map(this::toApiSchemaCommand).toList();
        return new CreateDatasourceCommand(request.name(), type, subType, request.description(), jdbcConfig, apiSchemas);
    }

    /**
     * 编辑数据源请求 → 命令（类型不可改，故命令不含 type/subType）。
     *
     * @param id      连接 id（来自路径）
     * @param request 请求体
     * @return 更新命令
     */
    default UpdateDatasourceCommand toUpdateCommand(Long id, UpdateDatasourceRequest request) {
        return new UpdateDatasourceCommand(id, request.name(), request.description(),
                toJdbcConfigCommand(request.jdbcConfig()));
    }

    /**
     * JDBC 配置请求 → 命令。
     *
     * @param request 请求体（可为 null）
     * @return JDBC 配置命令（null 输入返回 null）
     */
    default JdbcConfigCommand toJdbcConfigCommand(JdbcConfigRequest request) {
        if (request == null) {
            return null;
        }
        return new JdbcConfigCommand(request.host(), request.port(), request.database(),
                request.username(), request.password(), request.schema());
    }

    /**
     * 连接测试请求 → 命令。
     *
     * @param request 请求体
     * @return 测试命令
     */
    default TestConnectionCommand toTestConnectionCommand(TestConnectionRequest request) {
        return new TestConnectionCommand(request.id(), request.type(), request.subType(),
                request.host(), request.port(), request.database(), request.username(), request.password(),
                request.schema(), request.apiUrl(), request.apiMethod(), request.apiHeaders(), request.apiParams(),
                request.apiBody(), request.apiBodyType(), request.apiAuthType(), request.apiAuthUsername(),
                request.apiAuthPassword(), request.apiTimeout(), request.apiJsonPath());
    }

    /**
     * API 数据表请求 → 命令。
     *
     * @param request 请求体
     * @return API 表命令
     */
    default ApiSchemaCommand toApiSchemaCommand(ApiSchemaRequest request) {
        if (request == null) {
            return null;
        }
        ApiAuthType authType = request.authType() == null ? null : parseApiAuthType(request.authType());
        List<PreOperationConfig> preOperations = toPreOperationConfigList(request.preOperationConfigs());
        List<ApiFieldCommand> fields = request.fields() == null
                ? null
                : request.fields().stream().map(this::toApiFieldCommand).toList();
        return new ApiSchemaCommand(
                request.name(),
                request.method() == null ? null : parseHttpMethod(request.method()),
                request.url(),
                request.headers(),
                request.params(),
                request.body(),
                request.bodyType(),
                request.jsonPathConfig(),
                request.timeout(),
                request.retryCount(),
                authType,
                request.authUsername(),
                request.authPassword(),
                request.paginationType(),
                request.pageSizeParamName(),
                request.pageNumberParamName(),
                request.totalCountJsonPath(),
                request.pageSize(),
                request.maxPages(),
                preOperations,
                fields);
    }

    /**
     * API 字段请求 → 命令。
     *
     * @param request 请求体
     * @return 字段命令
     */
    default ApiFieldCommand toApiFieldCommand(ApiFieldRequest request) {
        if (request == null) {
            return null;
        }
        return new ApiFieldCommand(request.originalName(), request.displayName(),
                request.jsonPath(), request.fieldType(), request.description());
    }

    /**
     * 前置操作请求 → 领域配置。
     *
     * @param request 请求体
     * @return 前置操作领域配置（null 输入返回 null）
     */
    default PreOperationConfig toPreOperationConfig(PreOperationConfigRequest request) {
        if (request == null) {
            return null;
        }
        List<ParamMapping> mappings = request.paramMappings() == null
                ? null
                : request.paramMappings().stream().map(this::toParamMapping).toList();
        return new PreOperationConfig(
                request.enabled(),
                request.url(),
                request.method() == null ? null : parseHttpMethod(request.method()),
                request.headers(),
                request.params(),
                request.body(),
                StringUtils.isBlank(request.bodyType()) ? null : parseBodyType(request.bodyType()),
                mappings);
    }

    /**
     * 前置操作请求列表 → 领域配置列表。
     *
     * @param requests 请求体列表（可为 null）
     * @return 领域配置列表（null/空返回 null，保持命令"缺省"语义）
     */
    default List<PreOperationConfig> toPreOperationConfigList(List<PreOperationConfigRequest> requests) {
        if (requests == null) {
            return null;
        }
        return requests.stream().map(this::toPreOperationConfig).toList();
    }

    /**
     * 参数映射请求 → 领域配置。
     *
     * @param request 请求体
     * @return 参数映射
     */
    default ParamMapping toParamMapping(ParamMappingRequest request) {
        if (request == null) {
            return null;
        }
        return new ParamMapping(request.paramName(), request.paramLocation(), request.jsonPath());
    }

    /**
     * 响应试解析请求 → 命令。
     *
     * @param request 请求体
     * @return 解析命令
     */
    default ParseApiResponseCommand toParseCommand(ParseApiResponseRequest request) {
        return new ParseApiResponseCommand(
                request.connectionId(),
                request.url(),
                request.method(),
                request.headers(),
                request.params(),
                request.body(),
                request.bodyType(),
                request.authType(),
                request.authUsername(),
                request.authPassword(),
                request.timeout(),
                request.retryCount(),
                request.rootPath(),
                request.paginationType(),
                request.pageParamName(),
                request.sizeParamName(),
                request.totalCountJsonPath(),
                request.pageSize(),
                request.maxPages(),
                toPreOperationConfigList(request.preOperationConfigs()));
    }

    /**
     * 前置试跑请求 → 鉴权领域配置（试跑携带的鉴权上下文）。
     *
     * @param request 请求体
     * @return 领域鉴权配置（无鉴权类型时返回 NO_AUTH 空凭证）
     */
    default ApiAuthConfig toTestAuthConfig(PreOperationTestRequest request) {
        if (StringUtils.isBlank(request.authType())) {
            return new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null);
        }
        return new ApiAuthConfig(parseApiAuthType(request.authType()),
                request.authUsername(), request.authPassword());
    }

    // ==================== 查询条件装配 ====================

    /**
     * 列表查询入参 → 查询对象（枚举字符串归一，非法值抛 IllegalArgumentException → 400）。
     *
     * @param keyword 名称关键字（可空）
     * @param type    主类型过滤（可空）
     * @param status  状态过滤（可空）
     * @param page    页码（已由控制器兜底）
     * @param size    每页条数（已由控制器兜底）
     * @return 列表查询对象
     */
    default ListDatasourceQuery toListQuery(String keyword, String type, String status, int page, int size) {
        DatasourceType typeFilter = StringUtils.isBlank(type)
                ? null : DatasourceType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        DatasourceStatus statusFilter = StringUtils.isBlank(status)
                ? null : DatasourceStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        return new ListDatasourceQuery(keyword, typeFilter, statusFilter, page, size);
    }

    /**
     * 表列表查询入参 → 查询对象。
     *
     * @param connectionId 连接 id
     * @param keyword      表名关键字（可空）
     * @param page         页码（已由控制器兜底）
     * @param size         每页条数（已由控制器兜底）
     * @return 表列表查询对象
     */
    default TableListQuery toTableListQuery(Long connectionId, String keyword, int page, int size) {
        return new TableListQuery(connectionId, keyword, page, size);
    }

    // ==================== 领域对象 → 响应 ====================

    /**
     * 数据源连接 → 响应（脱敏）。
     *
     * @param connection 领域对象
     * @return 响应 DTO
     */
    default DatasourceResponse toDatasourceResponse(DatasourceConnection connection) {
        if (connection == null) {
            return null;
        }
        return new DatasourceResponse(
                connection.id(),
                connection.name(),
                enumToName(connection.type()),
                enumToName(connection.subType()),
                enumToName(connection.status()),
                toJdbcConfigResponse(connection.jdbcConnectionConfig()),
                connection.description(),
                connection.createdAt(),
                connection.updatedAt());
    }

    /**
     * 数据源连接列表 → 响应列表。
     *
     * @param connections 领域对象列表
     * @return 响应 DTO 列表
     */
    default List<DatasourceResponse> toDatasourceResponseList(List<DatasourceConnection> connections) {
        if (connections == null) {
            return List.of();
        }
        return connections.stream().map(this::toDatasourceResponse).toList();
    }

    /**
     * JDBC 连接配置 → 响应（丢弃密码）。
     *
     * @param config 领域配置（可为 null）
     * @return 响应 DTO（null 输入返回 null）
     */
    default JdbcConfigResponse toJdbcConfigResponse(JdbcConnectionConfig config) {
        if (config == null) {
            return null;
        }
        return new JdbcConfigResponse(config.host(), config.port(), config.database(),
                config.username(), config.schema());
    }

    /**
     * 支持类型枚举 → 响应（附带默认端口）。
     *
     * @param typeEnum 枚举项
     * @return 响应 DTO
     */
    default DatasourceTypeResponse toTypeResponse(DatasourceTypeEnum typeEnum) {
        if (typeEnum == null) {
            return null;
        }
        Integer defaultPort = null;
        if (DatasourceType.JDBC.name().equals(typeEnum.getType())) {
            defaultPort = JdbcType.valueOf(typeEnum.getSubType()).getDefaultPort();
        }
        return new DatasourceTypeResponse(typeEnum.getType(), typeEnum.getSubType(),
                typeEnum.getName(), typeEnum.getCategory(), defaultPort);
    }

    /**
     * 支持类型枚举列表 → 响应列表。
     *
     * @param typeEnums 枚举列表
     * @return 响应 DTO 列表
     */
    default List<DatasourceTypeResponse> toTypeResponseList(List<DatasourceTypeEnum> typeEnums) {
        if (typeEnums == null) {
            return List.of();
        }
        return typeEnums.stream().map(this::toTypeResponse).toList();
    }

    /**
     * 表元数据 → 响应。
     *
     * @param tableInfo 领域对象
     * @return 响应 DTO
     */
    default TableInfoResponse toTableInfoResponse(TableInfo tableInfo) {
        if (tableInfo == null) {
            return null;
        }
        return new TableInfoResponse(tableInfo.id(), tableInfo.databaseSchemaId(), tableInfo.tableName(),
                tableInfo.tableComment(), tableInfo.tableCustomComment(),
                tableInfo.createdAt(), tableInfo.updatedAt());
    }

    /**
     * 表元数据列表 → 响应列表。
     *
     * @param tableInfos 领域对象列表
     * @return 响应 DTO 列表
     */
    default List<TableInfoResponse> toTableInfoResponseList(List<TableInfo> tableInfos) {
        if (tableInfos == null) {
            return List.of();
        }
        return tableInfos.stream().map(this::toTableInfoResponse).toList();
    }

    /**
     * 字段元数据 → 响应。
     *
     * @param columnInfo 领域对象
     * @return 响应 DTO
     */
    default ColumnInfoResponse toColumnInfoResponse(ColumnInfo columnInfo) {
        if (columnInfo == null) {
            return null;
        }
        return new ColumnInfoResponse(columnInfo.id(), columnInfo.tableId(), columnInfo.columnName(),
                columnInfo.dataType(), columnInfo.columnComment(), columnInfo.columnCustomComment(),
                columnInfo.createdAt(), columnInfo.updatedAt());
    }

    /**
     * 字段元数据列表 → 响应列表。
     *
     * @param columnInfos 领域对象列表
     * @return 响应 DTO 列表
     */
    default List<ColumnInfoResponse> toColumnInfoResponseList(List<ColumnInfo> columnInfos) {
        if (columnInfos == null) {
            return List.of();
        }
        return columnInfos.stream().map(this::toColumnInfoResponse).toList();
    }

    /**
     * API 数据表 → 响应（鉴权脱敏）。
     *
     * @param apiSchema 领域对象
     * @return 响应 DTO
     */
    default ApiSchemaResponse toApiSchemaResponse(ApiSchema apiSchema) {
        if (apiSchema == null) {
            return null;
        }
        ApiRequestConfig config = apiSchema.config();
        return new ApiSchemaResponse(
                apiSchema.id(),
                apiSchema.connectionId(),
                apiSchema.name(),
                apiSchema.url(),
                enumToName(apiSchema.method()),
                config == null ? null : config.headers(),
                config == null ? null : config.params(),
                config == null ? null : config.body(),
                config == null ? null : enumToName(config.bodyType()),
                config == null ? null : config.jsonPathConfig(),
                config == null ? null : config.timeout(),
                config == null ? null : config.retryCount(),
                toAuthResponse(config == null ? null : config.authConfig()),
                toPaginationConfig(config == null ? null : config.paginationConfig()),
                config == null ? null : toPreOperationResponseList(config.preOperationConfigs()),
                apiSchema.createdAt(),
                apiSchema.updatedAt());
    }

    /**
     * API 数据表列表 → 响应列表。
     *
     * @param apiSchemas 领域对象列表
     * @return 响应 DTO 列表
     */
    default List<ApiSchemaResponse> toApiSchemaResponseList(List<ApiSchema> apiSchemas) {
        if (apiSchemas == null) {
            return List.of();
        }
        return apiSchemas.stream().map(this::toApiSchemaResponse).toList();
    }

    /**
     * 鉴权配置 → 脱敏响应（密码 → 是否已配置布尔标志）。
     *
     * @param authConfig 领域鉴权配置（可为 null）
     * @return 响应 DTO（null 输入返回 null）
     */
    default ApiAuthResponse toAuthResponse(ApiAuthConfig authConfig) {
        if (authConfig == null) {
            return null;
        }
        return new ApiAuthResponse(
                enumToName(authConfig.authType()),
                authConfig.username(),
                StringUtils.isNotBlank(authConfig.password()));
    }

    /**
     * 分页配置 → 响应。
     *
     * @param paginationConfig 领域分页配置（可为 null）
     * @return 响应 DTO（null 输入返回 null）
     */
    default ApiSchemaResponse.PaginationConfig toPaginationConfig(ApiPaginationConfig paginationConfig) {
        if (paginationConfig == null) {
            return null;
        }
        return new ApiSchemaResponse.PaginationConfig(
                enumToName(paginationConfig.paginationType()),
                paginationConfig.pageParamName(),
                paginationConfig.sizeParamName(),
                paginationConfig.totalCountJsonPath(),
                paginationConfig.pageSize(),
                paginationConfig.maxPages());
    }

    /**
     * 前置操作 → 响应。
     *
     * @param preOperationConfig 领域前置操作
     * @return 响应 DTO
     */
    default ApiSchemaResponse.PreOperationConfig toPreOperationResponse(PreOperationConfig preOperationConfig) {
        if (preOperationConfig == null) {
            return null;
        }
        List<ApiSchemaResponse.ParamMapping> mappings = preOperationConfig.paramMappings() == null
                ? List.of()
                : preOperationConfig.paramMappings().stream().map(this::toParamMappingResponse).toList();
        return new ApiSchemaResponse.PreOperationConfig(
                preOperationConfig.enabled(),
                preOperationConfig.url(),
                enumToName(preOperationConfig.method()),
                preOperationConfig.headers(),
                preOperationConfig.params(),
                preOperationConfig.body(),
                enumToName(preOperationConfig.bodyType()),
                mappings);
    }

    /**
     * 前置操作列表 → 响应列表。
     *
     * @param preOperationConfigs 领域前置操作列表
     * @return 响应 DTO 列表
     */
    default List<ApiSchemaResponse.PreOperationConfig> toPreOperationResponseList(
            List<PreOperationConfig> preOperationConfigs) {
        if (preOperationConfigs == null) {
            return List.of();
        }
        return preOperationConfigs.stream().map(this::toPreOperationResponse).toList();
    }

    /**
     * 参数映射 → 响应。
     *
     * @param paramMapping 领域参数映射
     * @return 响应 DTO
     */
    default ApiSchemaResponse.ParamMapping toParamMappingResponse(ParamMapping paramMapping) {
        if (paramMapping == null) {
            return null;
        }
        return new ApiSchemaResponse.ParamMapping(paramMapping.paramName(), paramMapping.paramLocation(),
                paramMapping.jsonPath());
    }

    /**
     * API 字段 → 响应。
     *
     * @param apiField 领域对象
     * @return 响应 DTO
     */
    default ApiFieldResponse toApiFieldResponse(ApiField apiField) {
        if (apiField == null) {
            return null;
        }
        return new ApiFieldResponse(apiField.id(), apiField.apiSchemaId(), apiField.originalName(),
                apiField.displayName(), apiField.jsonPath(), apiField.fieldType(), apiField.description());
    }

    /**
     * API 字段列表 → 响应列表。
     *
     * @param apiFields 领域对象列表
     * @return 响应 DTO 列表
     */
    default List<ApiFieldResponse> toApiFieldResponseList(List<ApiField> apiFields) {
        if (apiFields == null) {
            return List.of();
        }
        return apiFields.stream().map(this::toApiFieldResponse).toList();
    }

    /**
     * 解析字段树节点 → 响应节点（递归）。
     *
     * @param parsedField 领域节点（可为 null）
     * @return 响应节点（null 输入返回 null）
     */
    default ParsedFieldResponse toParsedFieldResponse(ParsedField parsedField) {
        if (parsedField == null) {
            return null;
        }
        List<ParsedFieldResponse> children = parsedField.children() == null
                ? List.of()
                : parsedField.children().stream().map(this::toParsedFieldResponse).toList();
        return new ParsedFieldResponse(parsedField.originalName(), parsedField.jsonPath(),
                parsedField.fieldType(), children);
    }

    /**
     * 解析字段树列表 → 响应列表。
     *
     * @param parsedFields 领域节点列表
     * @return 响应节点列表
     */
    default List<ParsedFieldResponse> toParsedFieldResponseList(List<ParsedField> parsedFields) {
        if (parsedFields == null) {
            return List.of();
        }
        return parsedFields.stream().map(this::toParsedFieldResponse).toList();
    }

    // ==================== 私有辅助 ====================

    /**
     * 解析数据源主类型（trim + 大写）。
     *
     * @param type 类型字符串
     * @return 主类型枚举
     */
    private DatasourceType parseDatasourceType(String type) {
        if (StringUtils.isBlank(type)) {
            return null;
        }
        return DatasourceType.valueOf(type.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 解析 JDBC 子类型（trim + 大写）。
     *
     * @param subType 子类型字符串
     * @return 子类型枚举（空白返回 null）
     */
    private JdbcType parseJdbcType(String subType) {
        if (StringUtils.isBlank(subType)) {
            return null;
        }
        return JdbcType.valueOf(subType.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 解析 HTTP 方法（trim + 大写）。
     *
     * @param method 方法字符串
     * @return 方法枚举（空白返回 null）
     */
    private HttpMethod parseHttpMethod(String method) {
        if (StringUtils.isBlank(method)) {
            return null;
        }
        return HttpMethod.valueOf(method.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 解析请求体类型（trim + 大写）。
     *
     * @param bodyType 类型字符串
     * @return 请求体类型枚举（空白返回 null）
     */
    private BodyType parseBodyType(String bodyType) {
        if (StringUtils.isBlank(bodyType)) {
            return null;
        }
        return BodyType.valueOf(bodyType.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 解析鉴权类型（沿用 {@link ApiAuthType#fromRequestString} 容错）。
     *
     * @param authType 鉴权类型字符串
     * @return 鉴权类型枚举（空白返回 null）
     */
    private ApiAuthType parseApiAuthType(String authType) {
        if (StringUtils.isBlank(authType)) {
            return null;
        }
        return ApiAuthType.fromRequestString(authType.trim());
    }

    /**
     * 解析分页类型（trim + 大写）。
     *
     * @param paginationType 分页类型字符串
     * @return 分页类型枚举（空白返回 null）
     */
    private ApiPaginationType parseApiPaginationType(String paginationType) {
        if (StringUtils.isBlank(paginationType)) {
            return null;
        }
        return ApiPaginationType.valueOf(paginationType.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 枚举 → 名称字符串（null 安全）。
     *
     * @param value 枚举值
     * @return 枚举名（null 输入返回 null）
     */
    private String enumToName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}