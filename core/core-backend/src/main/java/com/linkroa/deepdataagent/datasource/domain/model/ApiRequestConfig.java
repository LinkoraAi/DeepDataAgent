package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.ApiAuthType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * API请求配置值对象
 */
public record ApiRequestConfig(
        Map<String, String> headers,
        Map<String, String> params,
        String body,
        BodyType bodyType,
        String jsonPathConfig,
        int timeout,
        Integer retryCount,
        ApiAuthConfig authConfig,
        ApiPaginationConfig paginationConfig,
        List<PreOperationConfig> preOperationConfigs
) {
    private static final int DEFAULT_TIMEOUT = 180;

    public static ApiRequestConfig defaultConfig() {
        return new ApiRequestConfig(
                Collections.emptyMap(),
                Collections.emptyMap(),
                null,
                null,
                null,
                DEFAULT_TIMEOUT,
                null,
                new ApiAuthConfig(ApiAuthType.NO_AUTH, null, null),
                null,
                Collections.emptyList()
        );
    }
}
