package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiFieldRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiFieldEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.ApiFieldMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class JdbcApiFieldRepository implements ApiFieldRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ApiFieldMapper mapper;

    public JdbcApiFieldRepository(ApiFieldMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ApiField save(ApiField apiField) {
        ApiFieldEntity entity = DatasourcePersistenceMapper.toEntity(apiField);
        entity.setId(null);
        entity.setCreatedAt(now());
        entity.setUpdatedAt(now());
        entity.setIsDeleted(0);
        mapper.insert(entity);
        return findByApiSchemaId(apiField.apiSchemaId()).stream()
                .filter(item -> item.id().equals(entity.getId()))
                .findFirst()
                .orElse(apiField);
    }

    @Override
    public ApiField update(ApiField apiField) {
        ApiFieldEntity entity = DatasourcePersistenceMapper.toEntity(apiField);
        entity.setUpdatedAt(now());
        mapper.updateById(entity);
        return findByApiSchemaId(apiField.apiSchemaId()).stream()
                .filter(item -> item.id().equals(apiField.id()))
                .findFirst()
                .orElse(apiField);
    }

    @Override
    public List<ApiField> findByApiSchemaId(Long apiSchemaId) {
        return mapper.selectByApiSchemaId(apiSchemaId)
                .stream()
                .map(DatasourcePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteByApiSchemaId(Long apiSchemaId) {
        mapper.deleteByApiSchemaId(apiSchemaId);
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
