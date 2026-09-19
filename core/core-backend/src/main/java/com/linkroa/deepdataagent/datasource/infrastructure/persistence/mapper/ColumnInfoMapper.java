package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.ColumnInfoEntity;
import org.apache.ibatis.annotations.Mapper;

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
