package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentListFilter;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.EnvironmentEntity;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 运行环境 Mapper
 */
@Mapper
public interface EnvironmentMapper extends BaseMapper<EnvironmentEntity> {

    default EnvironmentEntity selectByEnvironmentId(String environmentId) {
        return selectOne(Wrappers.<EnvironmentEntity>lambdaQuery()
                .eq(EnvironmentEntity::getEnvironmentId, environmentId)
                .last("LIMIT 1"));
    }

    default EnvironmentEntity selectByNameAndOwnerId(String name, Long ownerId) {
        return selectOne(Wrappers.<EnvironmentEntity>lambdaQuery()
                .eq(EnvironmentEntity::getName, name)
                .eq(EnvironmentEntity::getOwnerId, ownerId)
                .last("LIMIT 1"));
    }

    default List<EnvironmentEntity> selectByIds(List<String> environmentIds) {
        if (environmentIds == null || environmentIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<EnvironmentEntity>lambdaQuery()
                .in(EnvironmentEntity::getEnvironmentId, environmentIds));
    }

    default EnvironmentEntity selectByEnvironmentIdForUpdate(String environmentId) {
        return selectOne(Wrappers.<EnvironmentEntity>lambdaQuery()
                .eq(EnvironmentEntity::getEnvironmentId, environmentId)
                .last("FOR UPDATE"));
    }

    /**
     * 游标分页查询运行环境（6.6 管理面 Cursor 约定，创建时间降序 keyset 行值比较）。
     * <p>after 方向 {@code (created_at, id) < (?,?)} 降序读取更旧页；before 方向反向比较后
     * 升序读取更新页（应用层翻转回降序）；metadata JSONB {@code @>} 包含与创建时间区间过滤可缺省。</p>
     */
    @SuppressWarnings("null")
    default List<EnvironmentEntity> selectByCursor(Long ownerId, EnvironmentListFilter filter, int limit) {
        LambdaQueryWrapper<EnvironmentEntity> wrapper = Wrappers.<EnvironmentEntity>lambdaQuery()
                .eq(EnvironmentEntity::getOwnerId, ownerId)
                .ge(filter.createdAfter() != null, EnvironmentEntity::getCreatedAt, filter.createdAfter())
                .le(filter.createdBefore() != null, EnvironmentEntity::getCreatedAt, filter.createdBefore());
        if (StringUtils.isNotBlank(filter.metadataJson())) {
            wrapper.apply("metadata @> cast({0} as jsonb)", filter.metadataJson());
        }
        if (filter.cursorCreatedAt() != null) {
            wrapper.apply(filter.reverse(), "(created_at, id) > ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
            wrapper.apply(!filter.reverse(), "(created_at, id) < ({0}, {1})",
                    filter.cursorCreatedAt(), filter.cursorRowId());
        }
        if (filter.reverse()) {
            wrapper.orderByAsc(EnvironmentEntity::getCreatedAt).orderByAsc(EnvironmentEntity::getId);
        } else {
            wrapper.orderByDesc(EnvironmentEntity::getCreatedAt).orderByDesc(EnvironmentEntity::getId);
        }
        return selectList(wrapper.last("LIMIT " + limit));
    }
}