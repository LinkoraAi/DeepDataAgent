package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatasourceConnectionEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.DatasourceConnectionMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcDatasourceConnectionRepository implements DatasourceConnectionRepository {

    private final DatasourceConnectionMapper mapper;
    private final DatasourcePersistenceMapper persistenceMapper;
    private final PasswordEncryptionUtil encryptionUtil;

    public JdbcDatasourceConnectionRepository(DatasourceConnectionMapper mapper,
                                              DatasourcePersistenceMapper persistenceMapper,
                                              PasswordEncryptionUtil encryptionUtil) {
        this.mapper = mapper;
        this.persistenceMapper = persistenceMapper;
        this.encryptionUtil = encryptionUtil;
    }

    @Override
    public DatasourceConnection save(DatasourceConnection connection) {
        DatasourceConnectionEntity entity = persistenceMapper.toEntity(connection, encryptionUtil);
        entity.setId(null);
        // 基础字段（created_at/updated_at/created_by/updated_by/is_deleted）由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findById(entity.getId()).orElse(connection);
    }

    @Override
    public DatasourceConnection update(DatasourceConnection connection) {
        DatasourceConnectionEntity entity = persistenceMapper.toEntity(connection, encryptionUtil);
        // updated_at/updated_by 由 MybatisPlusMetaObjectHandler 自动填充
        mapper.updateById(entity);
        return findById(connection.id()).orElse(connection);
    }

    @Override
    public Optional<DatasourceConnection> findById(Long id) {
        return Optional.ofNullable(persistenceMapper.toDomain(
                mapper.selectById(id), encryptionUtil
        ));
    }

    @Override
    public Optional<DatasourceConnection> findByName(String name) {
        return Optional.ofNullable(persistenceMapper.toDomain(
                mapper.selectByName(name), encryptionUtil
        ));
    }

    @Override
    public List<DatasourceConnection> findAll() {
        return mapper.selectAll()
                .stream()
                .map(e -> persistenceMapper.toDomain(e, encryptionUtil))
                .toList();
    }

    @Override
    public List<DatasourceConnection> findByCondition(String keyword, DatasourceType type, DatasourceStatus status, int page, int size) {
        return mapper.selectByCondition(
                        keyword,
                        type != null ? type.name() : null,
                        status != null ? status.name() : null,
                        (long) Math.max(0, page - 1) * size,
                        size)
                .stream()
                .map(e -> persistenceMapper.toDomain(e, encryptionUtil))
                .toList();
    }

    @Override
    public long countByCondition(String keyword, DatasourceType type, DatasourceStatus status) {
        return mapper.countByCondition(
                keyword,
                type != null ? type.name() : null,
                status != null ? status.name() : null);
    }

    @Override
    public void updateStatus(Long id, DatasourceStatus status) {
        mapper.updateStatus(id, status.name());
    }

    @Override
    public void deleteById(Long id) {
        // 逻辑删除（is_deleted 置 1）由 MyBatis-Plus @TableLogic 内建实现
        mapper.deleteById(id);
    }
}
