package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;

import java.util.List;
import java.util.Optional;

/**
 * 表信息仓储接口
 */
public interface TableInfoRepository {

    TableInfo save(TableInfo tableInfo);

    TableInfo update(TableInfo tableInfo);

    Optional<TableInfo> findById(Long id);

    List<TableInfo> findByDatabaseSchemaId(Long databaseSchemaId);

    List<TableInfo> findByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword, int page, int size);

    long countByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword);

    void updateTableCustomComment(Long id, String tableCustomComment);

    void softDeleteByDatabaseSchemaId(Long databaseSchemaId);

    void softDeleteById(Long id);
}
