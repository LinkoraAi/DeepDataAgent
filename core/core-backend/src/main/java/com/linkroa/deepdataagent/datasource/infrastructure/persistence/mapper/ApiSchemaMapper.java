package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiSchemaEntity;
import org.apache.ibatis.annotations.Mapper;

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
