package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.BodyType;
import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;

import java.util.List;
import java.util.Map;

/**
 * API数据表配置
 * <p>用于API请求执行的配置参数，不依赖数据库实体。</p>
 */
public record ApiTableConfig(
        String url,
        HttpMethod method,
        Map<String, String> headers,
        Map<String, String> params,
        String body,
        String jsonPathConfig,
        int timeout,
        ApiPaginationConfig paginationConfig,
        List<PreOperationConfig> preOperationConfigs,
        Integer retryCount,
        BodyType bodyType
) {}
