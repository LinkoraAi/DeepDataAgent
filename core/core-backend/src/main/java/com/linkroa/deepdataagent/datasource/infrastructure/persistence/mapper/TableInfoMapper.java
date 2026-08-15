package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.TableInfoEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Mapper
public interface TableInfoMapper extends BaseMapper<TableInfoEntity> {

    default List<TableInfoEntity> selectByDatabaseSchemaId(Long databaseSchemaId) {
        return selectList(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(e -> e.getDatabaseSchemaId(), databaseSchemaId)
                .orderByAsc(e -> e.getTableName()));
    }

    default int updateTableCustomComment(Long id, String tableCustomComment) {
        return update(null, Wrappers.<TableInfoEntity>lambdaUpdate()
                .set(e -> e.getTableCustomComment(), tableCustomComment)
                .set(e -> e.getUpdatedAt(), OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(e -> e.getId(), id));
    }

    default List<TableInfoEntity> selectByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword,
                                                                     long offset, int size) {
        return selectList(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(e -> e.getDatabaseSchemaId(), databaseSchemaId)
                .like(keyword != null && !keyword.isBlank(), e -> e.getTableName(), keyword)
                .orderByAsc(e -> e.getTableName())
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword) {
        return selectCount(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(e -> e.getDatabaseSchemaId(), databaseSchemaId)
                .like(keyword != null && !keyword.isBlank(), e -> e.getTableName(), keyword));
    }

    default TableInfoEntity selectByDatabaseSchemaIdAndTableName(Long databaseSchemaId, String tableName) {
        return selectOne(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(e -> e.getDatabaseSchemaId(), databaseSchemaId)
                .eq(e -> e.getTableName(), tableName)
                .last("LIMIT 1"));
    }
}