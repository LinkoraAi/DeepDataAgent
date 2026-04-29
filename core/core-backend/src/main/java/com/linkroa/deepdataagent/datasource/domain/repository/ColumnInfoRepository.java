package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;

import java.util.List;

/**
 * 列信息仓储接口
 */
public interface ColumnInfoRepository {

    ColumnInfo save(ColumnInfo columnInfo);

    ColumnInfo update(ColumnInfo columnInfo);

    List<ColumnInfo> findByTableId(Long tableId);

    void updateColumnCustomComment(Long id, String columnCustomComment);

    void softDeleteByTableId(Long tableId);

}
