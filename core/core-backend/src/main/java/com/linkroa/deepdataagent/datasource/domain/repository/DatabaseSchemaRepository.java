package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;

import java.util.List;
import java.util.Optional;

/**
 * 数据库Schema仓储接口
 */
public interface DatabaseSchemaRepository {

    DatabaseSchema save(DatabaseSchema schema);

    DatabaseSchema update(DatabaseSchema schema);

    Optional<DatabaseSchema> findById(Long id);

    List<DatabaseSchema> findByConnectionId(Long connectionId);

    Optional<DatabaseSchema> findByConnectionIdAndSchemaName(Long connectionId, String schemaName);

    void deleteByConnectionId(Long connectionId);

    void softDeleteByConnectionId(Long connectionId);
}
