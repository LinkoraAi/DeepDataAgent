package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiField;

import java.util.List;

/**
 * API字段仓储接口
 */
public interface ApiFieldRepository {

    ApiField save(ApiField apiField);

    ApiField update(ApiField apiField);

    List<ApiField> findByApiSchemaId(Long apiSchemaId);

    void deleteByApiSchemaId(Long apiSchemaId);
}
