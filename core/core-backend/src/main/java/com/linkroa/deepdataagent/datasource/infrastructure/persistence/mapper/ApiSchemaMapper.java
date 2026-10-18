package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiSchemaEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * API Schema Mapper
 *
 * <p>约束：LambdaQueryWrapper 的列引用一律使用方法引用
 * （{@code ApiSchemaEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface ApiSchemaMapper extends BaseMapper<ApiSchemaEntity> {

    /**
     * 沿父链解析 API 数据表所属数据源的归属者（api_schema → datasource_connection）。
     * <p>请求面按 API schema 标识寻址的归属判定入口；记录不存在或父链断裂返回 null，由调用方按 404 处理。</p>
     *
     * @param schemaId API schema 主键
     * @return 所属数据源的归属用户 ID；无命中返回 null
     */
    @Select("""
            SELECT c.owner_id
            FROM api_schema a
            JOIN datasource_connection c ON c.id = a.connection_id
            WHERE a.id = #{schemaId}
            """)
    Long selectOwnerIdByApiSchemaId(@Param("schemaId") Long schemaId);

    default ApiSchemaEntity selectByConnectionIdAndName(Long connectionId, String name) {
        return selectOne(Wrappers.<ApiSchemaEntity>lambdaQuery()
                .eq(ApiSchemaEntity::getConnectionId, connectionId)
                .eq(ApiSchemaEntity::getName, name)
                .last("LIMIT 1"));
    }

    default List<ApiSchemaEntity> selectByConnectionId(Long connectionId) {
        return selectList(Wrappers.<ApiSchemaEntity>lambdaQuery()
                .eq(ApiSchemaEntity::getConnectionId, connectionId)
                .orderByAsc(ApiSchemaEntity::getName));
    }
}
