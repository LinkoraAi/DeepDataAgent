package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.TableInfo;
import com.linkroa.deepdataagent.datasource.domain.repository.TableInfoRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.TableInfoEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.TableInfoMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcTableInfoRepository implements TableInfoRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TableInfoMapper mapper;

    public JdbcTableInfoRepository(TableInfoMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TableInfo save(TableInfo tableInfo) {
        TableInfoEntity entity = DatasourcePersistenceMapper.toEntity(tableInfo);
        entity.setId(null);
        entity.setCreatedAt(now());
        entity.setUpdatedAt(now());
        entity.setIsDeleted(0);
        mapper.insert(entity);
        return findById(entity.getId()).orElse(tableInfo);
    }

    @Override
    public TableInfo update(TableInfo tableInfo) {
        TableInfoEntity entity = DatasourcePersistenceMapper.toEntity(tableInfo);
        entity.setUpdatedAt(now());
        mapper.updateById(entity);
        return findById(tableInfo.id()).orElse(tableInfo);
    }

    @Override
    public Optional<TableInfo> findById(Long id) {
        return Optional.ofNullable(DatasourcePersistenceMapper.toDomain(mapper.selectById(id)));
    }

    @Override
    public List<TableInfo> findByDatabaseSchemaId(Long databaseSchemaId) {
        return mapper.selectByDatabaseSchemaId(databaseSchemaId)
                .stream()
                .map(DatasourcePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<TableInfo> findByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword, int page, int size) {
        return mapper.selectByDatabaseSchemaIdAndKeyword(databaseSchemaId, keyword, (long) (page - 1) * size, size)
                .stream()
                .map(DatasourcePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword) {
        return mapper.countByDatabaseSchemaIdAndKeyword(databaseSchemaId, keyword);
    }

    @Override
    public void updateTableCustomComment(Long id, String tableCustomComment) {
        mapper.updateTableCustomComment(id, tableCustomComment);
    }

    @Override
    public void softDeleteByDatabaseSchemaId(Long databaseSchemaId) {
        mapper.softDeleteByDatabaseSchemaId(databaseSchemaId);
    }

    @Override
    public void softDeleteById(Long id) {
        mapper.softDeleteById(id);
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
