package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.TableInfoEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 表信息 Mapper
 *
 * <p>约束：LambdaQueryWrapper / LambdaUpdateWrapper 的列引用一律使用方法引用
 * （{@code TableInfoEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface TableInfoMapper extends BaseMapper<TableInfoEntity> {

    default List<TableInfoEntity> selectByDatabaseSchemaId(Long databaseSchemaId) {
        return selectList(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(TableInfoEntity::getDatabaseSchemaId, databaseSchemaId)
                .orderByAsc(TableInfoEntity::getTableName));
    }

    default int updateTableCustomComment(Long id, String tableCustomComment) {
        return update(null, Wrappers.<TableInfoEntity>lambdaUpdate()
                .set(TableInfoEntity::getTableCustomComment, tableCustomComment)
                .set(TableInfoEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(TableInfoEntity::getId, id));
    }

    default List<TableInfoEntity> selectByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword,
                                                                     long offset, int size) {
        return selectList(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(TableInfoEntity::getDatabaseSchemaId, databaseSchemaId)
                .like(keyword != null && !keyword.isBlank(), TableInfoEntity::getTableName, keyword)
                .orderByAsc(TableInfoEntity::getTableName)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countByDatabaseSchemaIdAndKeyword(Long databaseSchemaId, String keyword) {
        return selectCount(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(TableInfoEntity::getDatabaseSchemaId, databaseSchemaId)
                .like(keyword != null && !keyword.isBlank(), TableInfoEntity::getTableName, keyword));
    }

    default TableInfoEntity selectByDatabaseSchemaIdAndTableName(Long databaseSchemaId, String tableName) {
        return selectOne(Wrappers.<TableInfoEntity>lambdaQuery()
                .eq(TableInfoEntity::getDatabaseSchemaId, databaseSchemaId)
                .eq(TableInfoEntity::getTableName, tableName)
                .last("LIMIT 1"));
    }
}
