package com.linkroa.deepdataagent.datasource.domain.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;

import java.util.List;
import java.util.Optional;

/**
 * 数据源连接仓储接口
 */
public interface DatasourceConnectionRepository {

    DatasourceConnection save(DatasourceConnection connection);

    DatasourceConnection update(DatasourceConnection connection);

    Optional<DatasourceConnection> findById(Long id);

    Optional<DatasourceConnection> findByName(String name);

    List<DatasourceConnection> findAll();

    List<DatasourceConnection> findByCondition(String keyword, DatasourceType type, DatasourceStatus status, int page, int size);

    long countByCondition(String keyword, DatasourceType type, DatasourceStatus status);

    void updateStatus(Long id, DatasourceStatus status);

    void deleteById(Long id);
}
