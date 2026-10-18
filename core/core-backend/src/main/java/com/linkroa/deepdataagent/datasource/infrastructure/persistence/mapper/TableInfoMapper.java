package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.TableInfoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 沿父链解析表所属数据源的归属者（table_info → database_schema → datasource_connection）。
     * <p>请求面按表标识寻址的归属判定入口；表不存在或父链断裂返回 null，由调用方按 404 处理。</p>
     *
     * @param tableId 表主键
     * @return 所属数据源的归属用户 ID；无命中返回 null
     */
    @Select("""
            SELECT c.owner_id
            FROM table_info t
            JOIN database_schema s ON s.id = t.database_schema_id
            JOIN datasource_connection c ON c.id = s.connection_id
            WHERE t.id = #{tableId}
            """)
    Long selectOwnerIdByTableId(@Param("tableId") Long tableId);

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
