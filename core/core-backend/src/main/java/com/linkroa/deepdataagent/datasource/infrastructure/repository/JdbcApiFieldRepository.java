package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ApiField;
import com.linkroa.deepdataagent.datasource.domain.repository.ApiFieldRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.convert.DatasourcePersistenceConvert;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiFieldEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.ApiFieldMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcApiFieldRepository implements ApiFieldRepository {

    private final ApiFieldMapper mapper;

    public JdbcApiFieldRepository(ApiFieldMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ApiField save(ApiField apiField) {
        ApiFieldEntity entity = DatasourcePersistenceConvert.INSTANCE.toEntity(apiField);
        entity.setId(null);
        // 基础字段由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findByApiSchemaId(apiField.apiSchemaId()).stream()
                .filter(item -> item.id().equals(entity.getId()))
                .findFirst()
                .orElse(apiField);
    }

    @Override
    public ApiField update(ApiField apiField) {
        ApiFieldEntity entity = DatasourcePersistenceConvert.INSTANCE.toEntity(apiField);
        // updated_at/updated_by 由 MybatisPlusMetaObjectHandler 自动填充
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
                .map(DatasourcePersistenceConvert.INSTANCE::toDomain)
                .toList();
    }

    @Override
    public void deleteByApiSchemaId(Long apiSchemaId) {
        // 逻辑删除由 MyBatis-Plus @TableLogic 内建实现
        mapper.delete(Wrappers.<ApiFieldEntity>lambdaQuery()
                .eq(ApiFieldEntity::getApiSchemaId, apiSchemaId));
    }
}