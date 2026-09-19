package com.linkroa.deepdataagent.datasource.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.datasource.infrastructure.persistence.entity.DatasourceConnectionEntity;
import org.apache.ibatis.annotations.Mapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 数据源连接 Mapper
 *
 * <p>约束：LambdaQueryWrapper / LambdaUpdateWrapper 的列引用一律使用方法引用
 * （{@code DatasourceConnectionEntity::getX}），不得写成 lambda 表达式（{@code e -> e.getX()}）——
 * 后者编译为合成方法 {@code lambda$N}，MyBatis-Plus 的 PropertyNamer 无法解析属性名，
 * 真实库运行期抛 ReflectionException。</p>
 */
@Mapper
public interface DatasourceConnectionMapper extends BaseMapper<DatasourceConnectionEntity> {

    default DatasourceConnectionEntity selectByName(String name) {
        return selectOne(Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .eq(DatasourceConnectionEntity::getName, name)
                .last("LIMIT 1"));
    }

    default List<DatasourceConnectionEntity> selectAll() {
        return selectList(Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .orderByDesc(DatasourceConnectionEntity::getUpdatedAt));
    }

    default List<DatasourceConnectionEntity> selectByCondition(String keyword, String type, String status,
                                                               long offset, int size) {
        return selectList(buildCondition(keyword, type, status)
                .orderByAsc(DatasourceConnectionEntity::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countByCondition(String keyword, String type, String status) {
        return selectCount(buildCondition(keyword, type, status));
    }

    default int updateStatus(Long id, String status) {
        return update(null, Wrappers.<DatasourceConnectionEntity>lambdaUpdate()
                .set(DatasourceConnectionEntity::getStatus, status)
                .set(DatasourceConnectionEntity::getUpdatedAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")))
                .eq(DatasourceConnectionEntity::getId, id));
    }

    private LambdaQueryWrapper<DatasourceConnectionEntity> buildCondition(String keyword, String type, String status) {
        return Wrappers.<DatasourceConnectionEntity>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), DatasourceConnectionEntity::getName, keyword)
                .eq(type != null && !type.isBlank(), DatasourceConnectionEntity::getType, type)
                .eq(status != null && !status.isBlank(), DatasourceConnectionEntity::getStatus, status);
    }
}
