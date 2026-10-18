package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;

import java.util.List;
import java.util.Optional;

/**
 * 列信息仓储接口
 */
public interface ColumnInfoRepository {

    ColumnInfo save(ColumnInfo columnInfo);

    ColumnInfo update(ColumnInfo columnInfo);

    List<ColumnInfo> findByTableId(Long tableId);

    /**
     * 沿父链解析列所属数据源的归属者
     * （column_info → table_info → database_schema → datasource_connection）。
     * <p>请求面按列标识寻址的归属判定入口；列不存在或父链断裂返回 {@link Optional#empty()}，
     * 与「非本人归属」对外不可区分（不泄露存在性）。</p>
     *
     * @param columnId 列主键，可为空
     * @return 所属数据源的归属用户 ID；无命中返回空
     */
    Optional<Long> findOwnerIdByColumnId(Long columnId);

    void updateColumnCustomComment(Long id, String columnCustomComment);

    void softDeleteByTableId(Long tableId);

}
