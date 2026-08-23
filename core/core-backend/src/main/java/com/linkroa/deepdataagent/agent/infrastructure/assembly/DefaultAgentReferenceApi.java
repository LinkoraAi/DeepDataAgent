package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.AgentReferenceApi;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import org.springframework.stereotype.Component;

/**
 * Agent 对外引用计数服务契约实现（{@link AgentReferenceApi}）。
 * <p>反向引用数据归属 agent BC，故实现置于 agent 基础设施层；memory / vault 作为消费方
 * 依赖 {@link AgentReferenceApi} 接口（正向），不反向触碰 agent 领域对象。</p>
 */
@Component
public class DefaultAgentReferenceApi implements AgentReferenceApi {

    private final AgentVersionRepository agentVersionRepository;
    private final ModelProfileRepository modelProfileRepository;

    public DefaultAgentReferenceApi(AgentVersionRepository agentVersionRepository,
                                    ModelProfileRepository modelProfileRepository) {
        this.agentVersionRepository = agentVersionRepository;
        this.modelProfileRepository = modelProfileRepository;
    }

    @Override
    public long countMemoryStoreReferences(String memoryStoreId) {
        return agentVersionRepository.countByMemoryStoreId(memoryStoreId);
    }

    @Override
    public long countSecretReferences(String secretId) {
        return modelProfileRepository.countBySecretId(secretId);
    }
}