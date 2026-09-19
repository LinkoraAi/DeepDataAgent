package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatabaseSchemaEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据库 Schema Mapper
 *
 * <p>约束：LambdaQueryWrapper 的列引用一律使用方法引用
 * （{@code DatabaseSchemaEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface DatabaseSchemaMapper extends BaseMapper<DatabaseSchemaEntity> {

    default List<DatabaseSchemaEntity> selectByConnectionId(Long connectionId) {
        return selectList(Wrappers.<DatabaseSchemaEntity>lambdaQuery()
                .eq(DatabaseSchemaEntity::getConnectionId, connectionId)
                .orderByAsc(DatabaseSchemaEntity::getSchemaName));
    }

    default DatabaseSchemaEntity selectByConnectionIdAndSchemaName(Long connectionId, String schemaName) {
        return selectOne(Wrappers.<DatabaseSchemaEntity>lambdaQuery()
                .eq(DatabaseSchemaEntity::getConnectionId, connectionId)
                .eq(DatabaseSchemaEntity::getSchemaName, schemaName)
                .last("LIMIT 1"));
    }
}
