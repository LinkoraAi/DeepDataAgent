package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiSchemaEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.ApiSchemaMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcApiSchemaRepository implements ApiSchemaRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ApiSchemaMapper mapper;

    public JdbcApiSchemaRepository(ApiSchemaMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ApiSchema save(ApiSchema apiSchema) {
        ApiSchemaEntity entity = DatasourcePersistenceMapper.toEntity(apiSchema);
        entity.setId(null);
        entity.setCreatedAt(now());
        entity.setUpdatedAt(now());
        entity.setIsDeleted(0);
        mapper.insert(entity);
        return findById(entity.getId()).orElse(apiSchema);
    }

    @Override
    public ApiSchema update(ApiSchema apiSchema) {
        ApiSchemaEntity entity = DatasourcePersistenceMapper.toEntity(apiSchema);
        entity.setUpdatedAt(now());
        mapper.updateById(entity);
        return findById(apiSchema.id()).orElse(apiSchema);
    }

    @Override
    public Optional<ApiSchema> findById(Long id) {
        return Optional.ofNullable(DatasourcePersistenceMapper.toDomain(mapper.selectById(id)));
    }

    @Override
    public Optional<ApiSchema> findByConnectionIdAndName(Long connectionId, String name) {
        return Optional.ofNullable(DatasourcePersistenceMapper.toDomain(
            mapper.selectByConnectionIdAndName(connectionId, name)));
    }

    @Override
    public List<ApiSchema> findByConnectionId(Long connectionId) {
        return mapper.selectByConnectionId(connectionId)
                .stream()
                .map(DatasourcePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteByConnectionId(Long connectionId) {
        mapper.deleteByConnectionId(connectionId);
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
