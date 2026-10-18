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

    /**
     * 沿父链解析表所属数据源的归属者
     * （table_info → database_schema → datasource_connection）。
     * <p>请求面按表标识寻址的归属判定入口；表不存在或父链断裂返回 {@link Optional#empty()}，
     * 与「非本人归属」对外不可区分（不泄露存在性）。</p>
     *
     * @param tableId 表主键，可为空
     * @return 所属数据源的归属用户 ID；无命中返回空
     */
    Optional<Long> findOwnerIdByTableId(Long tableId);

    List<TableInfo> findByDatabaseSchemaId(Long databaseSchemaId);

    List<TableInfo> findByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword, int page, int size);

    long countByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword);

    void updateTableCustomComment(Long id, String tableCustomComment);

    void softDeleteByDatabaseSchemaId(Long databaseSchemaId);

    void softDeleteById(Long id);
}
