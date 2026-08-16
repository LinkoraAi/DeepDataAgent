package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * Agent 定义 Mapper
 */
@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinitionEntity> {

    default AgentDefinitionEntity selectByAgentId(String agentId) {
        return selectOne(Wrappers.<AgentDefinitionEntity>lambdaQuery()
                .eq(e -> e.getAgentId(), agentId)
                .last("LIMIT 1"));
    }

    default AgentDefinitionEntity selectByName(String name) {
        return selectOne(Wrappers.<AgentDefinitionEntity>lambdaQuery()
                .eq(e -> e.getName(), name)
                .last("LIMIT 1"));
    }

    /**
     * 行锁查询（发布事务内串行化同一 Agent 的版本号计算，FOR UPDATE）
     */
    default AgentDefinitionEntity selectByAgentIdForUpdate(String agentId) {
        return selectOne(Wrappers.<AgentDefinitionEntity>lambdaQuery()
                .eq(e -> e.getAgentId(), agentId)
                .last("FOR UPDATE"));
    }

    default List<AgentDefinitionEntity> selectByCondition(String keyword, boolean includeArchived, long offset, int size) {
        return selectList(buildCondition(keyword, includeArchived)
                .orderByAsc(e -> e.getCreatedAt())
                .last("LIMIT " + size + " OFFSET " + offset));
    }

    default long countByCondition(String keyword, boolean includeArchived) {
        return selectCount(buildCondition(keyword, includeArchived));
    }

    private LambdaQueryWrapper<AgentDefinitionEntity> buildCondition(String keyword, boolean includeArchived) {
        return Wrappers.<AgentDefinitionEntity>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), e -> e.getName(), keyword)
                .eq(!includeArchived, e -> e.getArchived(), false);
    }
}