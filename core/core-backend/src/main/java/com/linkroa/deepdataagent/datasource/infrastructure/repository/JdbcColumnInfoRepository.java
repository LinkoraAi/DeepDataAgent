package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.repository.ColumnInfoRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ColumnInfoEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.ColumnInfoMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcColumnInfoRepository implements ColumnInfoRepository {

    private final ColumnInfoMapper mapper;
    private final DatasourcePersistenceMapper persistenceMapper;

    public JdbcColumnInfoRepository(ColumnInfoMapper mapper,
                                    DatasourcePersistenceMapper persistenceMapper) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
    }

    @Override
    public ColumnInfo save(ColumnInfo columnInfo) {
        ColumnInfoEntity entity = persistenceMapper.toEntity(columnInfo);
        entity.setId(null);
        // 基础字段由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findByTableId(columnInfo.tableId()).stream()
                .filter(item -> item.id().equals(entity.getId()))
                .findFirst()
                .orElse(columnInfo);
    }

    @Override
    public ColumnInfo update(ColumnInfo columnInfo) {
        ColumnInfoEntity entity = persistenceMapper.toEntity(columnInfo);
        // updated_at/updated_by 由 MybatisPlusMetaObjectHandler 自动填充
        mapper.updateById(entity);
        return findByTableId(columnInfo.tableId()).stream()
                .filter(item -> item.id().equals(columnInfo.id()))
                .findFirst()
                .orElse(columnInfo);
    }

    @Override
    public List<ColumnInfo> findByTableId(Long tableId) {
        return mapper.selectByTableId(tableId)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public void updateColumnCustomComment(Long id, String columnCustomComment) {
        mapper.updateColumnCustomComment(id, columnCustomComment);
    }

    @Override
    public void softDeleteByTableId(Long tableId) {
        // 逻辑删除由 MyBatis-Plus @TableLogic 内建实现
        mapper.delete(Wrappers.<ColumnInfoEntity>lambdaQuery()
                .eq(ColumnInfoEntity::getTableId, tableId));
    }
}
