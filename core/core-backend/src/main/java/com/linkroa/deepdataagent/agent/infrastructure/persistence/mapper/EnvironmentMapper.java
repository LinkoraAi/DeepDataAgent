package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.EnvironmentEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 运行环境 Mapper
 */
@Mapper
public interface EnvironmentMapper extends BaseMapper<EnvironmentEntity> {

    default EnvironmentEntity selectByEnvironmentId(String environmentId) {
        return selectOne(Wrappers.<EnvironmentEntity>lambdaQuery()
                .eq(e -> e.getEnvironmentId(), environmentId)
                .last("LIMIT 1"));
    }

    default EnvironmentEntity selectByName(String name) {
        return selectOne(Wrappers.<EnvironmentEntity>lambdaQuery()
                .eq(e -> e.getName(), name)
                .last("LIMIT 1"));
    }

    default List<EnvironmentEntity> selectByIds(List<String> environmentIds) {
        if (environmentIds == null || environmentIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<EnvironmentEntity>lambdaQuery()
                .in(e -> e.getEnvironmentId(), environmentIds));
    }

    default EnvironmentEntity selectByEnvironmentIdForUpdate(String environmentId) {
        return selectOne(Wrappers.<EnvironmentEntity>lambdaQuery()
                .eq(e -> e.getEnvironmentId(), environmentId)
                .last("FOR UPDATE"));
    }

    default List<EnvironmentEntity> selectPage(long offset, int size) {
        return selectList(Wrappers.<EnvironmentEntity>lambdaQuery()
                .orderByAsc(e -> e.getCreatedAt())
                .orderByAsc(e -> e.getId())
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countAll() {
        return selectCount(Wrappers.<EnvironmentEntity>lambdaQuery());
    }
}