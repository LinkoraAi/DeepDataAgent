package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;

import java.util.List;
import java.util.Optional;

/**
 * API Schema仓储接口
 */
public interface ApiSchemaRepository {

    ApiSchema save(ApiSchema apiSchema);

    ApiSchema update(ApiSchema apiSchema);

    Optional<ApiSchema> findById(Long id);

    Optional<ApiSchema> findByConnectionIdAndName(Long connectionId, String name);

    List<ApiSchema> findByConnectionId(Long connectionId);

    void deleteByConnectionId(Long connectionId);
}
