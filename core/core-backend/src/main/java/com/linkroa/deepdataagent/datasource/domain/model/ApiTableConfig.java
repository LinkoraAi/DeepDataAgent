package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.HttpMethod;

/**
 * API数据表配置
 * <p>用于API请求执行的配置参数，不依赖数据库实体。</p>
 *
 * @author system
 * @since 2026-05-12
 */
public record ApiTableConfig(
        String path,
        HttpMethod method,
        String jsonPath,
        ApiPaginationConfig paginationConfig
) {}