package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.DatasourceConnection;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceType;
import com.linkroa.deepdataagent.datasource.domain.repository.DatasourceConnectionRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.convert.DatasourcePersistenceConvert;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatasourceConnectionEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.DatasourceConnectionMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.util.PasswordEncryptionUtil;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcDatasourceConnectionRepository implements DatasourceConnectionRepository {

    private final DatasourceConnectionMapper mapper;
    private final PasswordEncryptionUtil encryptionUtil;

    public JdbcDatasourceConnectionRepository(DatasourceConnectionMapper mapper,
                                              PasswordEncryptionUtil encryptionUtil) {
        this.mapper = mapper;
        this.encryptionUtil = encryptionUtil;
    }

    @Override
    public DatasourceConnection save(DatasourceConnection connection) {
        DatasourceConnectionEntity entity = DatasourcePersistenceConvert.INSTANCE.toEntity(connection, encryptionUtil);
        entity.setId(null);
        // 基础字段（created_at/updated_at/created_by/updated_by/is_deleted）由 MybatisPlusMetaObjectHandler 自动填充
        mapper.insert(entity);
        return findById(entity.getId()).orElse(connection);
    }

    @Override
    public DatasourceConnection update(DatasourceConnection connection) {
        DatasourceConnectionEntity entity = DatasourcePersistenceConvert.INSTANCE.toEntity(connection, encryptionUtil);
        // updated_at/updated_by 由 MybatisPlusMetaObjectHandler 自动填充
        mapper.updateById(entity);
        return findById(connection.id()).orElse(connection);
    }

    @Override
    public Optional<DatasourceConnection> findById(Long id) {
        return Optional.ofNullable(DatasourcePersistenceConvert.INSTANCE.toDomain(
                mapper.selectById(id), encryptionUtil
        ));
    }

    @Override
    public Optional<DatasourceConnection> findByIdAndOwner(Long id, Long ownerId) {
        // 归属判定：主键不存在与归属不匹配同为 empty（不泄露存在性）
        if (id == null || ownerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(DatasourcePersistenceConvert.INSTANCE.toDomain(
                mapper.selectByIdAndOwner(id, ownerId), encryptionUtil
        ));
    }

    @Override
    public boolean existsOwned(Long id, Long ownerId) {
        if (id == null || ownerId == null) {
            return false;
        }
        return mapper.selectByIdAndOwner(id, ownerId) != null;
    }

    @Override
    public Optional<DatasourceConnection> findByName(String name) {
        return Optional.ofNullable(DatasourcePersistenceConvert.INSTANCE.toDomain(
                mapper.selectByName(name), encryptionUtil
        ));
    }

    @Override
    public Optional<DatasourceConnection> findByNameAndOwner(String name, Long ownerId) {
        if (ownerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(DatasourcePersistenceConvert.INSTANCE.toDomain(
                mapper.selectByNameAndOwner(name, ownerId), encryptionUtil
        ));
    }

    @Override
    public List<DatasourceConnection> findAll() {
        return mapper.selectAll()
                .stream()
                .map(e -> DatasourcePersistenceConvert.INSTANCE.toDomain(e, encryptionUtil))
                .toList();
    }

    @Override
    public List<DatasourceConnection> findByCondition(String keyword, DatasourceType type, DatasourceStatus status,
                                                      int page, int size, Long ownerId) {
        return mapper.selectByCondition(
                        keyword,
                        type != null ? type.name() : null,
                        status != null ? status.name() : null,
                        (long) Math.max(0, page - 1) * size,
                        size,
                        ownerId)
                .stream()
                .map(e -> DatasourcePersistenceConvert.INSTANCE.toDomain(e, encryptionUtil))
                .toList();
    }

    @Override
    public long countByCondition(String keyword, DatasourceType type, DatasourceStatus status, Long ownerId) {
        return mapper.countByCondition(
                keyword,
                type != null ? type.name() : null,
                status != null ? status.name() : null,
                ownerId);
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