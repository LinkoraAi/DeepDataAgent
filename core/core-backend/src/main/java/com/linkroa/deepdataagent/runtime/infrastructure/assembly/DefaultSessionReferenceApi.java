package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.runtime.api.SessionReferenceApi;
import com.linkroa.deepdataagent.runtime.domain.repository.AgentSessionRepository;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 会话引用查询服务契约的进程内实现。
 * <p>委托 {@link AgentSessionRepository#countByEnvironmentId(String)} 统计
 * agent_session 表中仍挂载指定环境的未删除会话数（逻辑删除自动过滤）。</p>
 */
@Service
public class DefaultSessionReferenceApi implements SessionReferenceApi {

    @Resource
    private AgentSessionRepository agentSessionRepository;

    @Override
    public long countSessionsByEnvironmentId(String environmentId) {
        return agentSessionRepository.countByEnvironmentId(environmentId);
    }

    @Override
    public long countSessionsByMemoryStoreId(String storeId) {
        return agentSessionRepository.countByMemoryStoreId(storeId);
    }

    @Override
    public long countSessionsByVaultId(String vaultId) {
        return agentSessionRepository.countByVaultId(vaultId);
    }
}
