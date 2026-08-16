package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;

import java.util.List;
import java.util.Optional;

/**
 * Agent 版本仓储接口
 */
public interface AgentVersionRepository {

    /**
     * 保存（新增发布版本）
     */
    AgentVersion save(AgentVersion version);

    /**
     * 按版本业务ID查询
     */
    Optional<AgentVersion> findByVersionId(String versionId);

    /**
     * 按 Agent + 发布号查询
     */
    Optional<AgentVersion> findByAgentIdAndVersionNumber(String agentId, int versionNumber);

    /**
     * 查询某 Agent 的版本列表（按发布号倒序，最新在前）
     */
    List<AgentVersion> listByAgentId(String agentId);

    /**
     * 查询某 Agent 的当前最大发布号（无版本时为 0）
     */
    int findMaxVersionNumber(String agentId);

    /**
     * 统计仍引用指定模型配置的未删除版本数（删除冲突校验）
     */
    long countByModelProfileId(String modelProfileId);

    /**
     * 逻辑删除某 Agent 的全部版本
     */
    void deleteByAgentId(String agentId);
}