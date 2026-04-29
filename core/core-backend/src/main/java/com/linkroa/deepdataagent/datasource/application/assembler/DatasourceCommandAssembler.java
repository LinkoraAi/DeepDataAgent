package com.linkroa.deepdataagent.datasource.application.assembler;

import com.linkroa.deepdataagent.datasource.application.command.ApiConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.ApiFieldCommand;
import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.JdbcConfigCommand;
import com.linkroa.deepdataagent.datasource.application.command.ParseApiResponseCommand;
import com.linkroa.deepdataagent.datasource.application.command.TestConnectionCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.query.ListDatasourceQuery;
import com.linkroa.deepdataagent.datasource.application.query.TableListQuery;
import com.linkroa.deepdataagent.datasource.application.validation.DatasourceValidator;
import com.linkroa.deepdataagent.datasource.controller.request.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;

import java.util.List;

/**
 * 数据源命令工厂
 * <p>负责将 Controller 层的 Request 对象转换为 Application 层的 Command/Query 对象，
 * 封装类型转换和默认值处理逻辑。</p>
 */
public final class DatasourceCommandAssembler {

    private DatasourceCommandAssembler() {
    }

    /**
     * 将 CreateDatasourceRequest 转换为 CreateDatasourceCommand
     */
    public static CreateDatasourceCommand toCreateCommand(CreateDatasourceRequest request) {
        return new CreateDatasourceCommand(
                request.name(),
                DatasourceValidator.parseDatasourceType(request.type()),
                DatasourceValidator.parseJdbcType(request.subType()),
                request.description(),
                toJdbcConfigCommand(request.jdbcConfig()),
                toApiConfigCommand(request.apiConfig())
        );
    }
    
    /**
     * 将 JdbcConfigRequest 转换为 JdbcConfigCommand
     */
    private static JdbcConfigCommand toJdbcConfigCommand(JdbcConfigRequest request) {
        if (request == null) return null;
        
        return new JdbcConfigCommand(
                request.host(),
                request.port(),
                request.database(),
                request.username(),
                request.password()
        );
    }
    
    /**
     * 将 ApiConfigRequest 转换为 ApiConfigCommand
     */
    private static ApiConfigCommand toApiConfigCommand(ApiConfigRequest request) {
        if (request == null) return null;
        
        return new ApiConfigCommand(
                request.apiUrl(),
                DatasourceValidator.parseHttpMethod(request.apiMethod()),
                request.apiHeaders(),
                request.apiParams(),
                request.apiBody(),
                parseAuthType(request.authConfig()),
                request.authConfig() != null ? request.authConfig().token() : null,
                request.authConfig() != null ? request.authConfig().username() : null,
                request.authConfig() != null ? request.authConfig().password() : null,
                request.paginationConfig() != null ? request.paginationConfig().paginationType() : null,
                request.paginationConfig() != null ? request.paginationConfig().sizeParamName() : null,
                request.paginationConfig() != null ? request.paginationConfig().pageParamName() : null,
                request.paginationConfig() != null ? request.paginationConfig().cursorParamName() : null,
                request.paginationConfig() != null ? request.paginationConfig().cursorJsonPath() : null,
                request.paginationConfig() != null ? request.paginationConfig().totalCountJsonPath() : null,
                request.paginationConfig() != null ? request.paginationConfig().pageSize() : null,
                request.paginationConfig() != null ? request.paginationConfig().maxPages() : null,
                request.apiTimeout(),
                request.jsonPathConfig(),
                request.schemaName(),
                request.schemaPath(),
                DatasourceValidator.parseHttpMethod(request.schemaMethod()),
                request.jsonPathConfig(),
                toApiFieldCommands(request.fields())
        );
    }
    
    /**
     * 将 ApiFieldRequest 列表转换为 ApiFieldCommand 列表
     */
    private static List<ApiFieldCommand> toApiFieldCommands(List<ApiFieldRequest> requests) {
        if (requests == null) return null;
        
        return requests.stream()
                .map(r -> new ApiFieldCommand(
                        r.originalName(),
                        r.displayName(),
                        r.jsonPath(),
                        r.fieldType(),
                        r.description()
                ))
                .toList();
    }
    
    /**
     * 解析认证类型
     */
    private static ApiAuthType parseAuthType(ApiAuthConfigRequest request) {
        if (request == null || request.type() == null) {
            return ApiAuthType.NO_AUTH;
        }
        
        return switch (request.type().toLowerCase()) {
            case "bearer", "bearer_token" -> ApiAuthType.BEARER_TOKEN;
            case "basic", "basic_auth" -> ApiAuthType.BASIC_AUTH;
            default -> ApiAuthType.NO_AUTH;
        };
    }

    /**
     * 将 UpdateDatasourceRequest 转换为 UpdateDatasourceCommand
     */
    public static UpdateDatasourceCommand toUpdateCommand(UpdateDatasourceRequest request) {
        return new UpdateDatasourceCommand(
                request.id(),
                request.name(),
                DatasourceValidator.parseDatasourceType(request.type()),
                DatasourceValidator.parseJdbcType(request.subType()),
                request.description(),
                toJdbcConfigCommand(request.jdbcConfig()),
                toApiConfigCommand(request.apiConfig())
        );
    }

    /**
     * 将 TestConnectionRequest 转换为 TestConnectionCommand
     */
    public static TestConnectionCommand toTestCommand(TestConnectionRequest request) {
        // 从 jdbcConfig 中提取 JDBC 相关字段
        String host = request.jdbcConfig() != null ? request.jdbcConfig().host() : null;
        Integer port = request.jdbcConfig() != null ? request.jdbcConfig().port() : null;
        String database = request.jdbcConfig() != null ? request.jdbcConfig().database() : null;
        String username = request.jdbcConfig() != null ? request.jdbcConfig().username() : null;
        String password = request.jdbcConfig() != null ? request.jdbcConfig().password() : null;
        
        // 从 apiConfig 中提取 API 相关字段
        String apiUrl = request.apiConfig() != null ? request.apiConfig().apiUrl() : null;
        String apiMethod = request.apiConfig() != null ? request.apiConfig().apiMethod() : null;
        Integer apiTimeout = request.apiConfig() != null ? request.apiConfig().apiTimeout() : null;
        String apiJsonPath = request.apiConfig() != null ? request.apiConfig().jsonPathConfig() : null;
        
        // 从 apiConfig 的 authConfig 中提取认证相关字段
        String apiAuthType = null;
        String apiAuthUsername = null;
        String apiAuthPassword = null;
        String apiAuthToken = null;
        
        if (request.apiConfig() != null && request.apiConfig().authConfig() != null) {
            var authConfig = request.apiConfig().authConfig();
            apiAuthType = authConfig.type();
            apiAuthUsername = authConfig.username();
            apiAuthPassword = authConfig.password();
            apiAuthToken = authConfig.token();
        }
        
        return new TestConnectionCommand(
                request.id(),
                request.type(),
                request.subType(),
                host,
                port,
                database,
                username,
                password,
                apiUrl,
                apiMethod,
                apiAuthType,
                apiAuthUsername,
                apiAuthPassword,
                apiAuthToken,
                apiTimeout,
                apiJsonPath
        );
    }

    /**
     * 将 ListDatasourceRequest 转换为 ListDatasourceQuery
     */
    public static ListDatasourceQuery toListQuery(ListDatasourceRequest request) {
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
    public static TableListQuery toTableListQuery(ListTablesRequest request) {
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
    public static ParseApiResponseCommand toParseCommand(ParseApiResponseRequest request) {
        return new ParseApiResponseCommand(
                request.connectionId(),
                request.apiUrl(),
                request.path(),
                request.method(),
                request.headers(),
                request.params(),
                request.body(),
                request.bodyType(),
                request.authType(),
                request.authToken(),
                request.authUsername(),
                request.authPassword(),
                request.timeout(),
                request.retryCount(),
                request.rootPath()
        );
    }
}
