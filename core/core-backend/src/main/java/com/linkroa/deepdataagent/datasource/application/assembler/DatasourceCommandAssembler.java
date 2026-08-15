package com.linkroa.deepdataagent.datasource.application.assembler;

import com.linkroa.deepdataagent.datasource.application.command.ApiFieldCommand;
import com.linkroa.deepdataagent.datasource.application.command.ApiSchemaCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.JdbcConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.application.validation.DatasourceValidator;
import com.linkroa.deepdataagent.datasource.controller.request.*;
import com.linkroa.deepdataagent.datasource.domain.model.ParamMapping;
import com.linkroa.deepdataagent.datasource.domain.model.PreOperationConfig;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import org.mapstruct.Mapper;

import java.util.List;
import java.util.Map;

/**
 * 数据源命令转换器
 * <p>负责将 Controller 层的 Request 对象转换为 Application 层的 Command/Query 对象。
 * 字段一一对应的映射由 MapStruct 自动生成；涉及枚举解析、默认值与嵌套取值的
 * 映射在 default 方法中显式实现。</p>
 */
@Mapper(componentModel = "spring")
public interface DatasourceCommandAssembler {

    // ===== 字段同名直转，MapStruct 自动生成 =====

    JdbcConfigCommand toJdbcConfigCommand(JdbcConfigRequest request);

    ApiFieldCommand toApiFieldCommand(ApiFieldRequest request);

    ParamMapping toParamMapping(ParamMappingRequest request);

    // ===== Create =====

    /**
     * 将 CreateDatasourceRequest 转换为 CreateDatasourceCommand
     * <p>注意：只有JDBC类型的数据源才有子类型，API类型没有子类型</p>
     */
    default CreateDatasourceCommand toCreateCommand(CreateDatasourceRequest request) {
        var type = DatasourceValidator.parseDatasourceType(request.type());
        var subType = type == DatasourceType.JDBC ? DatasourceValidator.parseJdbcType(request.subType()) : null;

        return new CreateDatasourceCommand(
                request.name(),
                type,
                subType,
                request.description(),
                toJdbcConfigCommand(request.jdbcConfig()),
                toApiSchemaCommands(request.apiSchemas())
        );
    }

    default ApiSchemaCommand toApiSchemaCommandFromCreate(CreateApiSchemaRequest request) {
        if (request == null || request.schema() == null) {
            throw new IllegalArgumentException("API表配置(schema)不能为空");
        }
        return toApiSchemaCommand(request.schema());
    }

    /**
     * 将 ApiSchemaRequest 列表转换为 ApiSchemaCommand 列表
     */
    default List<ApiSchemaCommand> toApiSchemaCommands(List<ApiSchemaRequest> requests) {
        if (requests == null) return null;

        return requests.stream()
                .map(this::toApiSchemaCommand)
                .toList();
    }

    /**
     * 将 ApiSchemaRequest 转换为 ApiSchemaCommand
     */
    default ApiSchemaCommand toApiSchemaCommand(ApiSchemaRequest request) {
        if (request == null) return null;

        return new ApiSchemaCommand(
                request.name(),
                DatasourceValidator.parseHttpMethod(request.method()),
                request.url(),
                request.headers(),
                request.params(),
                request.body(),
                request.bodyType(),
                request.jsonPathConfig(),
                request.timeout(),
                request.retryCount(),
                parseAuthType(request.authConfig()),
                request.authConfig() != null ? request.authConfig().username() : null,
                request.authConfig() != null ? request.authConfig().password() : null,
                request.paginationConfig() != null ? request.paginationConfig().paginationType() : null,
                request.paginationConfig() != null ? request.paginationConfig().sizeParamName() : null,
                request.paginationConfig() != null ? request.paginationConfig().pageParamName() : null,
                request.paginationConfig() != null ? request.paginationConfig().totalCountJsonPath() : null,
                request.paginationConfig() != null ? request.paginationConfig().pageSize() : null,
                request.paginationConfig() != null ? request.paginationConfig().maxPages() : null,
                toPreOperationConfigs(request.preOperationConfigs()),
                toApiFieldCommands(request.fields())
        );
    }

    /**
     * 将 PreOperationConfigRequest 列表转换为 PreOperationConfig 列表
     */
    default List<PreOperationConfig> toPreOperationConfigs(List<PreOperationConfigRequest> requests) {
        if (requests == null) return null;

        return requests.stream()
                .map(this::toPreOperationConfig)
                .toList();
    }

    /**
     * 将 PreOperationConfigRequest 转换为 PreOperationConfig
     */
    default PreOperationConfig toPreOperationConfig(PreOperationConfigRequest request) {
        if (request == null) {
            return null;
        }
        List<ParamMapping> paramMappings = null;
        if (request.paramMappings() != null) {
            paramMappings = request.paramMappings().stream()
                    .map(this::toParamMapping)
                    .toList();
        }
        return new PreOperationConfig(
                request.enabled() != null ? request.enabled() : false,
                request.url(),
                request.method() != null ? HttpMethod.valueOf(request.method()) : HttpMethod.GET,
                request.headers(),
                request.params(),
                request.body(),
                request.bodyType() != null && !request.bodyType().isBlank() ? BodyType.valueOf(request.bodyType().toUpperCase()) : null,
                paramMappings
        );
    }

    /**
     * 将 ApiFieldRequest 列表转换为 ApiFieldCommand 列表
     */
    default List<ApiFieldCommand> toApiFieldCommands(List<ApiFieldRequest> requests) {
        if (requests == null) return null;

        return requests.stream()
                .map(this::toApiFieldCommand)
                .toList();
    }

    /**
     * 解析认证类型
     */
    private ApiAuthType parseAuthType(ApiAuthConfigRequest request) {
        if (request == null || request.authType() == null) {
            return ApiAuthType.NO_AUTH;
        }
        return ApiAuthType.fromRequestString(request.authType());
    }

    /**
     * 将 UpdateDatasourceRequest 转换为 UpdateDatasourceCommand
     */
    default UpdateDatasourceCommand toUpdateCommand(UpdateDatasourceRequest request) {
        return new UpdateDatasourceCommand(
                request.id(),
                request.name(),
                request.description(),
                toJdbcConfigCommand(request.jdbcConfig())
        );
    }

    /**
     * 将 TestConnectionRequest 转换为 TestConnectionCommand
     * <p>注意：只有JDBC类型的数据源才有子类型，API类型没有子类型</p>
     */
    default TestConnectionCommand toTestCommand(TestConnectionRequest request) {
        String host = request.jdbcConfig() != null ? request.jdbcConfig().host() : null;
        Integer port = request.jdbcConfig() != null ? request.jdbcConfig().port() : null;
        String database = request.jdbcConfig() != null ? request.jdbcConfig().database() : null;
        String username = request.jdbcConfig() != null ? request.jdbcConfig().username() : null;
        String password = request.jdbcConfig() != null ? request.jdbcConfig().password() : null;
        String schema = request.jdbcConfig() != null ? request.jdbcConfig().schema() : null;

        String apiUrl = request.apiSchema() != null ? request.apiSchema().url() : null;
        String apiMethod = request.apiSchema() != null ? request.apiSchema().method() : null;
        Map<String, String> apiHeaders = request.apiSchema() != null ? request.apiSchema().headers() : null;
        Map<String, String> apiParams = request.apiSchema() != null ? request.apiSchema().params() : null;
        String apiBody = request.apiSchema() != null ? request.apiSchema().body() : null;
        String apiBodyType = request.apiSchema() != null ? request.apiSchema().bodyType() : null;
        Integer apiTimeout = request.apiSchema() != null ? request.apiSchema().timeout() : null;
        String apiJsonPath = request.apiSchema() != null ? request.apiSchema().jsonPathConfig() : null;

        String apiAuthType = null;
        String apiAuthUsername = null;
        String apiAuthPassword = null;

        if (request.apiSchema() != null && request.apiSchema().authConfig() != null) {
            var authConfig = request.apiSchema().authConfig();
            apiAuthType = authConfig.authType();
            apiAuthUsername = authConfig.username();
            apiAuthPassword = authConfig.password();
        }

        var type = DatasourceValidator.parseDatasourceType(request.type());
        var subType = type == DatasourceType.JDBC ? request.subType() : null;

        return new TestConnectionCommand(
                request.id(),
                request.type(),
                subType,
                host,
                port,
                database,
                username,
                password,
                schema,
                apiUrl,
                apiMethod,
                apiHeaders,
                apiParams,
                apiBody,
                apiBodyType,
                apiAuthType,
                apiAuthUsername,
                apiAuthPassword,
                apiTimeout,
                apiJsonPath
        );
    }

    /**
     * 将 ListDatasourceRequest 转换为 ListDatasourceQuery
     */
    default ListDatasourceQuery toListQuery(ListDatasourceRequest request) {
        return new ListDatasourceQuery(
                request.keyword(),
                DatasourceValidator.parseDatasourceTypeOrNull(request.type()),
                DatasourceValidator.parseDatasourceStatusOrNull(request.status()),
                request.page() != null ? request.page() : 1,
                request.size() != null ? request.size() : 20
        );
    }

    /**
     * 将 ListTablesRequest 转换为 TableListQuery
     */
    default TableListQuery toTableListQuery(ListTablesRequest request) {
        return new TableListQuery(
                request.connectionId(),
                request.keyword(),
                request.page() != null ? request.page() : 1,
                request.size() != null ? request.size() : 50
        );
    }

    /**
     * 将 ParseApiResponseRequest 转换为 ParseApiResponseCommand
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
                request.authConfig() != null ? request.authConfig().authType() : null,
                request.authConfig() != null ? request.authConfig().username() : null,
                request.authConfig() != null ? request.authConfig().password() : null,
                request.timeout(),
                request.retryCount(),
                request.rootPath(),
                request.paginationConfig() != null ? request.paginationConfig().paginationType() : null,
                request.paginationConfig() != null ? request.paginationConfig().pageParamName() : null,
                request.paginationConfig() != null ? request.paginationConfig().sizeParamName() : null,
                request.paginationConfig() != null ? request.paginationConfig().totalCountJsonPath() : null,
                request.paginationConfig() != null ? request.paginationConfig().pageSize() : null,
                request.paginationConfig() != null ? request.paginationConfig().maxPages() : null,
                toPreOperationConfigs(request.preOperationConfigs())
        );
    }
}