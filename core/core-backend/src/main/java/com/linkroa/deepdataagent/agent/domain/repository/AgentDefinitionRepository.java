package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Agent 定义仓储接口
 */
public interface AgentDefinitionRepository {

    /**
     * 保存（新增）
     */
    AgentDefinition save(AgentDefinition definition);

    /**
     * 更新（含 latest_version / archived 变更）
     */
    AgentDefinition update(AgentDefinition definition);

    /**
     * 按业务ID查询
     */
    Optional<AgentDefinition> findByAgentId(String agentId);

    /**
     * 行锁查询（发布事务内使用，串行化同一 Agent 的版本号计算）
     */
    Optional<AgentDefinition> findByAgentIdForUpdate(String agentId);

    /**
     * 按名称查询（唯一）
     */
    Optional<AgentDefinition> findByName(String name);

    /**
     * 分页查询（默认不含已归档）
     */
    List<AgentDefinition> findByCondition(String keyword, boolean includeArchived, int page, int size);

    /**
     * 分页统计
     */
    long countByCondition(String keyword, boolean includeArchived);

    /**
     * 归档 / 取消归档
     */
    void updateArchived(String agentId, boolean archived);

    /**
     * 逻辑删除
     */
    void deleteByAgentId(String agentId);
}