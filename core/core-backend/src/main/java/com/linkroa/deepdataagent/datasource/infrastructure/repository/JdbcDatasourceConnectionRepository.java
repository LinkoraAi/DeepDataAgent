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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcDatasourceConnectionRepository implements DatasourceConnectionRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatasourceConnectionMapper mapper;
    private final PasswordEncryptionUtil encryptionUtil;

    public JdbcDatasourceConnectionRepository(DatasourceConnectionMapper mapper,
                                              PasswordEncryptionUtil encryptionUtil) {
        this.mapper = mapper;
        this.encryptionUtil = encryptionUtil;
    }

    @Override
    public DatasourceConnection save(DatasourceConnection connection) {
        DatasourceConnectionEntity entity = DatasourcePersistenceMapper.toEntity(connection, encryptionUtil);
        entity.setId(null);
        entity.setCreatedAt(now());
        entity.setUpdatedAt(now());
        entity.setIsDeleted(0);
        mapper.insert(entity);
        return findById(entity.getId()).orElse(connection);
    }

    @Override
    public DatasourceConnection update(DatasourceConnection connection) {
        DatasourceConnectionEntity entity = DatasourcePersistenceMapper.toEntity(connection, encryptionUtil);
        entity.setUpdatedAt(now());
        mapper.updateById(entity);
        return findById(connection.id()).orElse(connection);
    }

    @Override
    public Optional<DatasourceConnection> findById(Long id) {
        return Optional.ofNullable(DatasourcePersistenceMapper.toDomain(
                mapper.selectByIdAndNotDeleted(id), encryptionUtil
        ));
    }

    @Override
    public Optional<DatasourceConnection> findByName(String name) {
        return Optional.ofNullable(DatasourcePersistenceMapper.toDomain(
                mapper.selectByName(name), encryptionUtil
        ));
    }

    @Override
    public List<DatasourceConnection> findAll() {
        return mapper.selectAll()
                .stream()
                .map(e -> DatasourcePersistenceMapper.toDomain(e, encryptionUtil))
                .toList();
    }

    @Override
    public List<DatasourceConnection> findByCondition(String keyword, DatasourceType type, DatasourceStatus status, int page, int size) {
        return mapper.selectByCondition(
                        keyword,
                        type != null ? type.name() : null,
                        status != null ? status.name() : null,
                        (long) (page - 1) * size,
                        size)
                .stream()
                .map(e -> DatasourcePersistenceMapper.toDomain(e, encryptionUtil))
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
        mapper.updateStatus(id, status.name(), now());
    }

    @Override
    public void deleteById(Long id) {
        mapper.softDeleteById(id, now());
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
