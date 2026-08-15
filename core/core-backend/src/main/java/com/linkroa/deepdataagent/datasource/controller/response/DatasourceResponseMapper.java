package com.linkroa.deepdataagent.datasource.controller.response;

import com.linkroa.deepdataagent.datasource.domain.model.*;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 领域对象 → 响应 DTO 的 MapStruct 转换器。
 * <p>字段一一对应的映射由 MapStruct 自动生成；涉及嵌套取值（如
 * DatasourceConnection.jdbcConnectionConfig）与固定 type 的映射以 @Mapping 声明。</p>
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DatasourceResponseMapper {

    /** 固定掩码文本，表示已配置密码 */
    String MASKED_PASSWORD = "****...****";

    // ===== DatasourceConnectionResponse =====

    @Mapping(target = "host", source = "jdbcConnectionConfig.host")
    @Mapping(target = "port", source = "jdbcConnectionConfig.port")
    @Mapping(target = "database", source = "jdbcConnectionConfig.database")
    @Mapping(target = "schema", source = "jdbcConnectionConfig.schema")
    @Mapping(target = "username", source = "jdbcConnectionConfig.username")
    @Mapping(target = "maskedPassword", source = "jdbcConnectionConfig.password", qualifiedByName = "maskPassword")
    DatasourceConnectionResponse toConnectionResponse(DatasourceConnection connection);

    @Named("maskPassword")
    default String maskPassword(String password) {
        return StringUtils.isNotBlank(password) ? MASKED_PASSWORD : null;
    }

    // ===== ColumnInfoResponse =====

    ColumnInfoResponse toColumnInfoResponse(ColumnInfo columnInfo);

    @Mapping(target = "tableId", source = "apiSchemaId")
    @Mapping(target = "columnName", source = "originalName")
    @Mapping(target = "dataType", source = "fieldType")
    @Mapping(target = "columnComment", source = "description")
    ColumnInfoResponse columnInfoResponseFromApiField(ApiField apiField);

    // ===== TableInfoResponse =====

    TableInfoResponse toTableInfoResponse(TableInfo tableInfo);

    // ===== TableResponse =====

    @Mapping(target = "type", constant = "JDBC")
    TableResponse tableResponseFromTableInfo(TableInfo tableInfo);

    @Mapping(target = "type", constant = "API")
    @Mapping(target = "method", source = "method")
    @Mapping(target = "jsonPath", source = "config.jsonPathConfig")
    TableResponse tableResponseFromApiSchema(ApiSchema apiSchema);

    // ===== API Schema 详情（嵌套结构） =====

    default ApiSchemaDetailResponse toApiSchemaDetailResponse(ApiSchema schema, List<ApiField> fields) {
        ApiRequestConfig config = schema.config() != null ? schema.config() : ApiRequestConfig.defaultConfig();
        return new ApiSchemaDetailResponse(
                schema.id(), schema.connectionId(), schema.name(), schema.url(),
                schema.method() != null ? schema.method().name() : null,
                config.headers(), config.params(), config.body(),
                config.bodyType() != null ? config.bodyType().name() : null,
                config.jsonPathConfig(), config.timeout(), config.retryCount(),
                config.authConfig() != null ? apiAuthConfigResponse(config.authConfig()) : null,
                config.paginationConfig() != null ? apiPaginationConfigResponse(config.paginationConfig()) : null,
                config.preOperationConfigs() != null
                        ? config.preOperationConfigs().stream().map(this::preOperationConfigResponse).toList()
                        : null,
                fields != null ? fields.stream().map(this::apiFieldResponse).toList() : null,
                schema.createdAt(), schema.updatedAt()
        );
    }

    @Mapping(target = "authType", source = "authType")
    ApiAuthConfigResponse apiAuthConfigResponse(ApiAuthConfig authConfig);

    @Mapping(target = "paginationType", source = "paginationType")
    ApiPaginationConfigResponse apiPaginationConfigResponse(ApiPaginationConfig paginationConfig);

    @Mapping(target = "method", source = "method")
    @Mapping(target = "bodyType", source = "bodyType")
    PreOperationConfigResponse preOperationConfigResponse(PreOperationConfig config);

    ParamMappingResponse paramMappingResponse(ParamMapping mapping);

    ApiFieldResponse apiFieldResponse(ApiField field);
}