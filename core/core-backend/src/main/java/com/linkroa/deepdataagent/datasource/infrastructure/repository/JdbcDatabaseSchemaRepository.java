package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatabaseSchema;
import com.linkroa.deepdataagent.datasource.domain.repository.DatabaseSchemaRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatabaseSchemaEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.DatabaseSchemaMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcDatabaseSchemaRepository implements DatabaseSchemaRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabaseSchemaMapper mapper;

    public JdbcDatabaseSchemaRepository(DatabaseSchemaMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DatabaseSchema save(DatabaseSchema schema) {
        DatabaseSchemaEntity entity = DatasourcePersistenceMapper.toEntity(schema);
        entity.setId(null);
        entity.setCreatedAt(now());
        entity.setUpdatedAt(now());
        entity.setIsDeleted(0);
        mapper.insert(entity);
        return findById(entity.getId()).orElse(schema);
    }

    @Override
    public DatabaseSchema update(DatabaseSchema schema) {
        DatabaseSchemaEntity entity = DatasourcePersistenceMapper.toEntity(schema);
        entity.setUpdatedAt(now());
        mapper.updateById(entity);
        return findById(schema.id()).orElse(schema);
    }

    @Override
    public Optional<DatabaseSchema> findById(Long id) {
        return Optional.ofNullable(DatasourcePersistenceMapper.toDomain(mapper.selectById(id)));
    }

    @Override
    public List<DatabaseSchema> findByConnectionId(Long connectionId) {
        return mapper.selectByConnectionId(connectionId)
                .stream()
                .map(DatasourcePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<DatabaseSchema> findByConnectionIdAndSchemaName(Long connectionId, String schemaName) {
        return Optional.ofNullable(DatasourcePersistenceMapper.toDomain(
                mapper.selectByConnectionIdAndSchemaName(connectionId, schemaName)
        ));
    }

    @Override
    public void deleteByConnectionId(Long connectionId) {
        mapper.deleteByConnectionId(connectionId);
    }

    @Override
    public void softDeleteByConnectionId(Long connectionId) {
        mapper.softDeleteByConnectionId(connectionId);
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
