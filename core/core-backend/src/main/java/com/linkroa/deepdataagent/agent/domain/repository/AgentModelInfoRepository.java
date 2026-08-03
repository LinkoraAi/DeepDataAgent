package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.AgentModelInfo;

import java.util.List;
import java.util.Optional;

/**
 * 模型信息仓储接口
 * <p>定义在 domain 层，实现在 infrastructure 层。</p>
 */
public interface AgentModelInfoRepository {

    Optional<AgentModelInfo> findById(Long id);

    List<AgentModelInfo> findAllEnabled();

    Optional<AgentModelInfo> findDefault();

    List<AgentModelInfo> findByProviderName(String providerName);

    /** 查询所有启用的服务商（去重） */
    List<AgentModelInfo> findDistinctProviders();

    AgentModelInfo save(AgentModelInfo info);

    void update(AgentModelInfo info);

    void markDeleted(Long id);
}
