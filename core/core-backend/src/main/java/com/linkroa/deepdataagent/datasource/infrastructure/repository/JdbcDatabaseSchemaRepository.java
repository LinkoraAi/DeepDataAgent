package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.convert.DatasourcePersistenceConvert;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatabaseSchemaEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.DatabaseSchemaMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcDatabaseSchemaRepository implements DatabaseSchemaRepository {

    private final DatabaseSchemaMapper mapper;

    public JdbcDatabaseSchemaRepository(DatabaseSchemaMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DatabaseSchema save(DatabaseSchema schema) {
        DatabaseSchemaEntity entity = DatasourcePersistenceConvert.INSTANCE.toEntity(schema);
        entity.setId(null);
        // 基础字段由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findById(entity.getId()).orElse(schema);
    }

    @Override
    public DatabaseSchema update(DatabaseSchema schema) {
        DatabaseSchemaEntity entity = DatasourcePersistenceConvert.INSTANCE.toEntity(schema);
        // updated_at/updated_by 由 MybatisPlusMetaObjectHandler 自动填充
        mapper.updateById(entity);
        return findById(schema.id()).orElse(schema);
    }

    @Override
    public Optional<DatabaseSchema> findById(Long id) {
        return Optional.ofNullable(DatasourcePersistenceConvert.INSTANCE.toDomain(mapper.selectById(id)));
    }

    @Override
    public List<DatabaseSchema> findByConnectionId(Long connectionId) {
        return mapper.selectByConnectionId(connectionId)
                .stream()
                .map(DatasourcePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public Optional<DatabaseSchema> findByConnectionIdAndSchemaName(Long connectionId, String schemaName) {
        return Optional.ofNullable(DatasourcePersistenceConvert.INSTANCE.toDomain(
                mapper.selectByConnectionIdAndSchemaName(connectionId, schemaName)
        ));
    }

    @Override
    @SuppressWarnings("null") // MyBatis-Plus SFunction 方法引用误报（改写为普通 lambda 会导致列名解析失败），运行时与 null 语义无关
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