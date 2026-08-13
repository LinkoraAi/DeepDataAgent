package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.datasource.domain.model.ColumnInfo;
import com.linkroa.deepdataagent.datasource.domain.repository.ColumnInfoRepository;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourcePersistenceMapper;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ColumnInfoEntity;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper.ColumnInfoMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class JdbcColumnInfoRepository implements ColumnInfoRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ColumnInfoMapper mapper;

    public JdbcColumnInfoRepository(ColumnInfoMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ColumnInfo save(ColumnInfo columnInfo) {
        ColumnInfoEntity entity = DatasourcePersistenceMapper.toEntity(columnInfo);
        entity.setId(null);
        entity.setCreatedAt(now());
        entity.setUpdatedAt(now());
        entity.setIsDeleted(0);
        mapper.insert(entity);
        return findByTableId(columnInfo.tableId()).stream()
                .filter(item -> item.id().equals(entity.getId()))
                .findFirst()
                .orElse(columnInfo);
    }

    @Override
    public ColumnInfo update(ColumnInfo columnInfo) {
        ColumnInfoEntity entity = DatasourcePersistenceMapper.toEntity(columnInfo);
        entity.setUpdatedAt(now());
        mapper.updateById(entity);
        return findByTableId(columnInfo.tableId()).stream()
                .filter(item -> item.id().equals(columnInfo.id()))
                .findFirst()
                .orElse(columnInfo);
    }

    @Override
    public List<ColumnInfo> findByTableId(Long tableId) {
        return mapper.selectByTableId(tableId)
                .stream()
                .map(DatasourcePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public void updateColumnCustomComment(Long id, String columnCustomComment) {
        mapper.updateColumnCustomComment(id, columnCustomComment);
    }

    @Override
    public void softDeleteByTableId(Long tableId) {
        mapper.softDeleteByTableId(tableId);
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
