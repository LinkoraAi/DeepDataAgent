package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiSchema;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiSchemaRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiSchemaEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.ApiSchemaMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcApiSchemaRepository implements ApiSchemaRepository {

    private final ApiSchemaMapper mapper;
    private final DatasourcePersistenceMapper persistenceMapper;
    private final PasswordEncryptionUtil encryptionUtil;

    public JdbcApiSchemaRepository(ApiSchemaMapper mapper,
                                   DatasourcePersistenceMapper persistenceMapper,
                                   PasswordEncryptionUtil encryptionUtil) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
        this.encryptionUtil = encryptionUtil;
    }

    @Override
    public ApiSchema save(ApiSchema apiSchema) {
        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, encryptionUtil);
        entity.setId(null);
        // 基础字段由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findById(entity.getId()).orElse(apiSchema);
    }

    @Override
    public ApiSchema update(ApiSchema apiSchema) {
        ApiSchemaEntity entity = persistenceMapper.toEntity(apiSchema, encryptionUtil);
        // updated_at/updated_by 由 MybatisPlusMetaObjectHandler 自动填充
        mapper.updateById(entity);
        return findById(apiSchema.id()).orElse(apiSchema);
    }

    @Override
    public Optional<ApiSchema> findById(Long id) {
        return Optional.ofNullable(persistenceMapper.toDomain(mapper.selectById(id), encryptionUtil));
    }

    @Override
    public Optional<ApiSchema> findByConnectionIdAndName(Long connectionId, String name) {
        return Optional.ofNullable(persistenceMapper.toDomain(
            mapper.selectByConnectionIdAndName(connectionId, name), encryptionUtil));
    }

    @Override
    public List<ApiSchema> findByConnectionId(Long connectionId) {
        return mapper.selectByConnectionId(connectionId)
                .stream()
                .map(e -> persistenceMapper.toDomain(e, encryptionUtil))
                .toList();
    }

    @Override
    public void deleteByConnectionId(Long connectionId) {
        // 逻辑删除由 MyBatis-Plus @TableLogic 内建实现
        mapper.delete(Wrappers.<ApiSchemaEntity>lambdaQuery()
                .eq(ApiSchemaEntity::getConnectionId, connectionId));
    }

    @Override
    public void deleteById(Long id) {
        mapper.deleteById(id);
    }
}
