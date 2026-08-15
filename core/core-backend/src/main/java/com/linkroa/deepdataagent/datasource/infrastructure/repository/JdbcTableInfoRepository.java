package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.TableInfoEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.TableInfoMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcTableInfoRepository implements TableInfoRepository {

    private final TableInfoMapper mapper;
    private final DatasourcePersistenceMapper persistenceMapper;

    public JdbcTableInfoRepository(TableInfoMapper mapper,
                                   DatasourcePersistenceMapper persistenceMapper) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
    }

    @Override
    public TableInfo save(TableInfo tableInfo) {
        TableInfoEntity entity = persistenceMapper.toEntity(tableInfo);
        entity.setId(null);
        // 基础字段由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findById(entity.getId()).orElse(tableInfo);
    }

    @Override
    public TableInfo update(TableInfo tableInfo) {
        TableInfoEntity entity = persistenceMapper.toEntity(tableInfo);
        // updated_at/updated_by 由 MybatisPlusMetaObjectHandler 自动填充
        mapper.updateById(entity);
        return findById(tableInfo.id()).orElse(tableInfo);
    }

    @Override
    public Optional<TableInfo> findById(Long id) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectById(id)));
    }

    @Override
    public List<TableInfo> findByDatabaseSchemaId(Long databaseSchemaId) {
        return mapper.selectByDatabaseSchemaId(databaseSchemaId)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<TableInfo> findByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword, int page, int size) {
        return mapper.selectByDatabaseSchemaIdAndKeyword(databaseSchemaId, keyword, (long) Math.max(0, page - 1) * size, size)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword) {
        return mapper.countByDatabaseSchemaIdAndKeyword(databaseSchemaId, keyword);
    }

    @Override
    public void updateTableCustomComment(Long id, String tableCustomComment) {
        mapper.updateTableCustomComment(id, tableCustomComment);
    }

    @Override
    public void softDeleteByDatabaseSchemaId(Long databaseSchemaId) {
        // 逻辑删除由 MyBatis-Plus @TableLogic 内建实现
        mapper.delete(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(TableInfoEntity::getDatabaseSchemaId, databaseSchemaId));
    }

    @Override
    public void softDeleteById(Long id) {
        mapper.deleteById(id);
    }
}
