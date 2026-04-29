package com.linkroa.deepdataagent.datasource.application.assembler;

import com.linkroa.deepdataagent.datasource.application.command.CreateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.application.command.UpdateDatasourceCommand;
import com.linkroa.deepdataagent.datasource.domain.model.*;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiPaginationType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * 数据源组装器
 * <p>负责Command到领域模型的转换。</p>
 */
public class DatasourceAssembler {

    public static DatasourceConnection toDatasourceConnection(CreateDatasourceCommand command) {
        JdbcConnectionConfig jdbcConfig = null;
        ApiConnectionConfig apiConfig = null;
        ApiAuthConfig authConfig = null;

        if (command.type() == DatasourceType.JDBC && command.jdbcConfig() != null) {
            jdbcConfig = new JdbcConnectionConfig(
                    command.jdbcConfig().host(),
                    ObjectUtils.firstNonNull(command.jdbcConfig().port(), 0),
                    command.jdbcConfig().database(),
                    command.jdbcConfig().username(),
                    command.jdbcConfig().password()
            );
        } else if (command.type() == DatasourceType.API && command.apiConfig() != null) {
            ApiPaginationConfig paginationConfig = null;
            if (StringUtils.isNotBlank(command.apiConfig().apiPaginationType()) && !"NONE".equalsIgnoreCase(command.apiConfig().apiPaginationType())) {
                paginationConfig = new ApiPaginationConfig(
                        parsePaginationType(command.apiConfig().apiPaginationType()),
                        command.apiConfig().apiPageNumberParamName(),
                        command.apiConfig().apiPageSizeParamName(),
                        command.apiConfig().apiCursorParamName(),
                        command.apiConfig().apiCursorJsonPath(),
                        command.apiConfig().apiTotalCountJsonPath(),
                        command.apiConfig().apiPageSize(),
                        command.apiConfig().apiMaxPages()
                );
            }
            apiConfig = new ApiConnectionConfig(
                    command.apiConfig().apiUrl(),
                    ObjectUtils.firstNonNull(command.apiConfig().apiMethod(), HttpMethod.GET),
                    command.apiConfig().apiHeaders(),
                    command.apiConfig().apiParams(),
                    command.apiConfig().apiBody(),
                    paginationConfig,
                    ObjectUtils.firstNonNull(command.apiConfig().apiTimeout(), 10),
                    command.apiConfig().apiJsonPath()
            );
            authConfig = new ApiAuthConfig(
                    ObjectUtils.firstNonNull(command.apiConfig().apiAuthType(), ApiAuthType.NO_AUTH),
                    command.apiConfig().apiAuthUsername(),
                    command.apiConfig().apiAuthPassword(),
                    command.apiConfig().apiAuthToken()
            );
        }

        return DatasourceConnection.create(
                command.name(),
                command.type(),
                command.subType(),
                command.description(),
                jdbcConfig,
                apiConfig,
                authConfig
        );
    }

    public static DatasourceConnection toDatasourceConnection(UpdateDatasourceCommand command, DatasourceConnection existing) {
        JdbcConnectionConfig jdbcConfig = existing.jdbcConnectionConfig();
        ApiConnectionConfig apiConfig = existing.apiConnectionConfig();
        ApiAuthConfig authConfig = existing.apiAuthConfig();

        if (command.type() == DatasourceType.JDBC && command.jdbcConfig() != null) {
            jdbcConfig = new JdbcConnectionConfig(
                    StringUtils.defaultIfBlank(command.jdbcConfig().host(), existing.jdbcConnectionConfig().host()),
                    ObjectUtils.firstNonNull(command.jdbcConfig().port(), existing.jdbcConnectionConfig().port()),
                    StringUtils.defaultIfBlank(command.jdbcConfig().database(), existing.jdbcConnectionConfig().database()),
                    StringUtils.defaultIfBlank(command.jdbcConfig().username(), existing.jdbcConnectionConfig().username()),
                    StringUtils.defaultIfBlank(command.jdbcConfig().password(), existing.jdbcConnectionConfig().password())
            );
        } else if (command.type() == DatasourceType.API && command.apiConfig() != null) {
            ApiPaginationConfig paginationConfig = null;
            if (StringUtils.isNotBlank(command.apiConfig().apiPaginationType()) && !"NONE".equalsIgnoreCase(command.apiConfig().apiPaginationType())) {
                paginationConfig = new ApiPaginationConfig(
                        parsePaginationType(command.apiConfig().apiPaginationType()),
                        command.apiConfig().apiPageNumberParamName(),
                        command.apiConfig().apiPageSizeParamName(),
                        command.apiConfig().apiCursorParamName(),
                        command.apiConfig().apiCursorJsonPath(),
                        command.apiConfig().apiTotalCountJsonPath(),
                        command.apiConfig().apiPageSize(),
                        command.apiConfig().apiMaxPages()
                );
            }
            apiConfig = new ApiConnectionConfig(
                    StringUtils.defaultIfBlank(command.apiConfig().apiUrl(), existing.apiConnectionConfig().url()),
                    ObjectUtils.firstNonNull(command.apiConfig().apiMethod(), existing.apiConnectionConfig().method()),
                    ObjectUtils.firstNonNull(command.apiConfig().apiHeaders(), existing.apiConnectionConfig().headers()),
                    ObjectUtils.firstNonNull(command.apiConfig().apiParams(), existing.apiConnectionConfig().params()),
                    StringUtils.defaultIfBlank(command.apiConfig().apiBody(), existing.apiConnectionConfig().body()),
                    paginationConfig,
                    ObjectUtils.firstNonNull(command.apiConfig().apiTimeout(), existing.apiConnectionConfig().timeout()),
                    StringUtils.defaultIfBlank(command.apiConfig().apiJsonPath(), existing.apiConnectionConfig().jsonPath())
            );
            authConfig = new ApiAuthConfig(
                    ObjectUtils.firstNonNull(command.apiConfig().apiAuthType(), existing.apiAuthConfig().authType()),
                    StringUtils.defaultIfBlank(command.apiConfig().apiAuthUsername(), existing.apiAuthConfig().username()),
                    StringUtils.defaultIfBlank(command.apiConfig().apiAuthPassword(), existing.apiAuthConfig().password()),
                    StringUtils.defaultIfBlank(command.apiConfig().apiAuthToken(), existing.apiAuthConfig().token())
            );
        }

        return new DatasourceConnection(
                existing.id(),
                StringUtils.defaultIfBlank(command.name(), existing.name()),
                existing.type(),
                existing.subType(),
                existing.status(),
                jdbcConfig,
                apiConfig,
                authConfig,
                StringUtils.defaultIfBlank(command.description(), existing.description()),
                existing.createdAt(),
                existing.updatedAt(),
                existing.createdBy(),
                existing.updatedBy()
        );
    }

    private static ApiPaginationType parsePaginationType(String value) {
        if (StringUtils.isBlank(value) || "NONE".equalsIgnoreCase(value)) {
            return ApiPaginationType.NONE;
        }
        if ("PAGE_NUMBER".equalsIgnoreCase(value)) {
            return ApiPaginationType.PAGE_BASED;
        }
        if ("CURSOR".equalsIgnoreCase(value)) {
            return ApiPaginationType.CURSOR_BASED;
        }
        return ApiPaginationType.valueOf(value);
    }
}
