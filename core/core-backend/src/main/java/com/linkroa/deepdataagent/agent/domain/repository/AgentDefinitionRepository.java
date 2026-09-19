package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentListFilter;

import java.time.OffsetDateTime;
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
     * 游标分页查询（创建时间降序 + 业务 ID 次键 keyset，按 owner 隔离；
     * {@code limit} 由调用方完成 +1 探针放大，{@code reverse} 方向升序读取后由应用层翻转）。
     */
    List<AgentDefinition> findByCursor(Long ownerId, AgentListFilter filter, int limit);

    /**
     * 设置归档时间（{@code null} = 取消归档；归档仅以时间戳表达）
     */
    void updateArchivedAt(String agentId, OffsetDateTime archivedAt);

    /**
     * 更新激活版本号（部署激活 / 回滚，latest_version 不变）
     */
    void updateActiveVersion(String agentId, int versionNumber);

    /**
     * 逻辑删除
     */
    void deleteByAgentId(String agentId);
}