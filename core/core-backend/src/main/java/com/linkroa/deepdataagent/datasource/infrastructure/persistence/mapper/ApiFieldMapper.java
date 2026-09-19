package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ApiFieldEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * API 字段 Mapper
 *
 * <p>约束：LambdaQueryWrapper 的列引用一律使用方法引用
 * （{@code ApiFieldEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface ApiFieldMapper extends BaseMapper<ApiFieldEntity> {

    default List<ApiFieldEntity> selectByApiSchemaId(Long apiSchemaId) {
        return selectList(Wrappers.<ApiFieldEntity>lambdaQuery()
                .eq(ApiFieldEntity::getApiSchemaId, apiSchemaId)
                .orderByAsc(ApiFieldEntity::getId));
    }
}
