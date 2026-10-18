package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ColumnInfoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 列信息 Mapper
 *
 * <p>约束：LambdaQueryWrapper / LambdaUpdateWrapper 的列引用一律使用方法引用
 * （{@code ColumnInfoEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface ColumnInfoMapper extends BaseMapper<ColumnInfoEntity> {

    /**
     * 沿父链解析列所属数据源的归属者
     * （column_info → table_info → database_schema → datasource_connection）。
     * <p>请求面按列标识寻址的归属判定入口；列不存在或父链断裂返回 null，由调用方按 404 处理。</p>
     *
     * @param columnId 列主键
     * @return 所属数据源的归属用户 ID；无命中返回 null
     */
    @Select("""
            SELECT c.owner_id
            FROM column_info col
            JOIN table_info t ON t.id = col.table_id
            JOIN database_schema s ON s.id = t.database_schema_id
            JOIN datasource_connection c ON c.id = s.connection_id
            WHERE col.id = #{columnId}
            """)
    Long selectOwnerIdByColumnId(@Param("columnId") Long columnId);

    default List<ColumnInfoEntity> selectByTableId(Long tableId) {
        return selectList(Wrappers.<ColumnInfoEntity>lambdaQuery()
                .eq(ColumnInfoEntity::getTableId, tableId)
                .orderByAsc(ColumnInfoEntity::getId));
    }

    default int updateColumnCustomComment(Long id, String columnCustomComment) {
        return update(null, Wrappers.<ColumnInfoEntity>lambdaUpdate()
                .set(ColumnInfoEntity::getColumnCustomComment, columnCustomComment)
                .set(ColumnInfoEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(ColumnInfoEntity::getId, id));
    }
}
