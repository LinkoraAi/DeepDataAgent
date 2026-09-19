package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.AgentReferenceApi;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * Agent 对外引用计数服务契约实现（{@link AgentReferenceApi}）。
 * <p>反向引用数据归属 agent BC，故实现置于 agent 基础设施层；skill 等消费方
 * 依赖 {@link AgentReferenceApi} 接口（正向），不反向触碰 agent 领域对象。</p>
 */
@Component
public class DefaultAgentReferenceApi implements AgentReferenceApi {

    @Resource
    private AgentVersionRepository agentVersionRepository;

    @Override
    public long countSkillBindings(String skillId) {
        return agentVersionRepository.countSkillBindings(skillId);
    }

    @Override
    public long countSkillVersionBindings(String skillId, String version) {
        return agentVersionRepository.countSkillVersionBindings(skillId, version);
    }
}
