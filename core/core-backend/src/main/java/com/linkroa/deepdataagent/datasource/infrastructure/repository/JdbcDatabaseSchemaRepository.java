package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatabaseSchemaEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.DatabaseSchemaMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcDatabaseSchemaRepository implements DatabaseSchemaRepository {

    private final DatabaseSchemaMapper mapper;
    private final DatasourcePersistenceMapper persistenceMapper;

    public JdbcDatabaseSchemaRepository(DatabaseSchemaMapper mapper,
                                        DatasourcePersistenceMapper persistenceMapper) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
    }

    @Override
    public DatabaseSchema save(DatabaseSchema schema) {
        DatabaseSchemaEntity entity = persistenceMapper.toEntity(schema);
        entity.setId(null);
        // 基础字段由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findById(entity.getId()).orElse(schema);
    }

    @Override
    public DatabaseSchema update(DatabaseSchema schema) {
        DatabaseSchemaEntity entity = persistenceMapper.toEntity(schema);
        // updated_at/updated_by 由 MybatisPlusMetaObjectHandler 自动填充
        mapper.updateById(entity);
        return findById(schema.id()).orElse(schema);
    }

    @Override
    public Optional<DatabaseSchema> findById(Long id) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectById(id)));
    }

    @Override
    public List<DatabaseSchema> findByConnectionId(Long connectionId) {
        return mapper.selectByConnectionId(connectionId)
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<DatabaseSchema> findByConnectionIdAndSchemaName(Long connectionId, String schemaName) {
        return Optional.ofNullable(persistenceMapper.toDomain(
                mapper.selectByConnectionIdAndSchemaName(connectionId, schemaName)
        ));
    }

    @Override
    public void deleteByConnectionId(Long connectionId) {
        // 逻辑删除由 MyBatis-Plus @TableLogic 内建实现（走 delete(wrapper)）
        mapper.delete(Wrappers.<DatabaseSchemaEntity>lambdaQuery()
                .eq(DatabaseSchemaEntity::getConnectionId, connectionId));
    }

    @Override
    public void softDeleteByConnectionId(Long connectionId) {
        deleteByConnectionId(connectionId);
    }
}
