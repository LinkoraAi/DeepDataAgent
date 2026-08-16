package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentVersionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.ModelProfileEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * Agent 版本 Mapper
 */
@Mapper
public interface AgentVersionMapper extends BaseMapper<AgentVersionEntity> {

    default AgentVersionEntity selectByVersionId(String versionId) {
        return selectOne(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(e -> e.getVersionId(), versionId)
                .last("LIMIT 1"));
    }

    default AgentVersionEntity selectByAgentIdAndVersionNumber(String agentId, int versionNumber) {
        return selectOne(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(e -> e.getAgentId(), agentId)
                .eq(e -> e.getVersionNumber(), versionNumber)
                .last("LIMIT 1"));
    }

    /**
     * 查询某 Agent 的全部版本（按发布号倒序，最新在前）
     */
    default List<AgentVersionEntity> selectByAgentId(String agentId) {
        return selectList(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(e -> e.getAgentId(), agentId)
                .orderByDesc(e -> e.getVersionNumber()));
    }

    default AgentVersionEntity selectMaxVersion(String agentId) {
        return selectOne(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(e -> e.getAgentId(), agentId)
                .orderByDesc(e -> e.getVersionNumber())
                .last("LIMIT 1"));
    }

    default Long countByModelProfileId(String modelProfileId) {
        return selectCount(Wrappers.<AgentVersionEntity>lambdaQuery()
                .eq(e -> e.getModelProfileId(), modelProfileId));
    }
}