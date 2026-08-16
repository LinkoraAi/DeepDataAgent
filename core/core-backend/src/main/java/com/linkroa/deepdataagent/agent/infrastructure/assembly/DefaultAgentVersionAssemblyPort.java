package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.ModelIndicator;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.infrastructure.util.ModelCredentialEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Agent「版本 + 模型」解析端口实现（发布号十进制解析 + 存在性/归档校验 + 凭证解密）。
 * <p>运行装配「仅校验 profile 存在，容忍 DISABLED」（决策 D6）；Agent 已归档时
 * 拒绝创建新会话（无回退）。</p>
 */
@Service
public class DefaultAgentVersionAssemblyPort implements AgentVersionAssemblyPort {

    @Resource
    private AgentDefinitionRepository agentDefinitionRepository;
    @Resource
    private AgentVersionRepository agentVersionRepository;
    @Resource
    private ModelProfileRepository modelProfileRepository;
    @Resource
    private ModelCredentialEncryptionUtil credentialEncryptionUtil;

    @Override
    public ResolvedAgentAssemblyDTO resolve(String agentId, String versionNumber) {
        // 校验链路（存在性/归档/版本/profile）与装配契约拼装复用一份查询结果
        VersionAndProfile validated = validateResolvable(agentId, versionNumber);

        // 解密在基础设施层完成，明文凭证仅注入运行时工厂装配配置，不参与任何响应序列化
        String credential = credentialEncryptionUtil.decrypt(validated.profile().encryptedCredential());
        return new ResolvedAgentAssemblyDTO(
                agentId,
                validated.versionRow().versionNumber(),
                validated.versionRow().name(),
                validated.versionRow().system(),
                ModelIndicator.of(validated.profile().apiFormat(), validated.profile().modelName()).resolved(),
                validated.profile().toolCallRounds(),
                credential,
                validated.profile().apiEndpointUrl()
        );
    }

    @Override
    public void assertResolvable(String agentId, String versionNumber) {
        validateResolvable(agentId, versionNumber);
    }

    /**
     * 校验装配链路（发布号十进制 / Agent 存在且未归档 / 版本存在 / profile 存在），
     * 任一失败抛 404；返回校验通过的版本行 + profile 供装配复用。不执行凭证解密。
     */
    private VersionAndProfile validateResolvable(String agentId, String versionNumber) {
        int version = parseVersionNumber(versionNumber);

        AgentDefinition definition = agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        if (definition.archived()) {
            throw new ResourceNotFoundException("Agent已归档，不可创建新会话");
        }

        AgentVersion versionRow = agentVersionRepository.findByAgentIdAndVersionNumber(agentId, version)
                .orElseThrow(() -> new ResourceNotFoundException("Agent版本不存在"));

        ModelProfile profile = modelProfileRepository.findByProfileId(versionRow.modelProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
        return new VersionAndProfile(versionRow, profile);
    }

    /** 校验通过的版本行 + 模型配置组合。 */
    private record VersionAndProfile(AgentVersion versionRow, ModelProfile profile) {
    }

    /**
     * 发布号须为十进制字符串；非十进制视为版本不存在（404），解析失败同样 404。
     */
    private int parseVersionNumber(String versionNumber) {
        if (StringUtils.isBlank(versionNumber) || !versionNumber.matches("\\d+")) {
            throw new ResourceNotFoundException("发布号格式非法");
        }
        try {
            int version = Integer.parseInt(versionNumber);
            if (version < 1) {
                throw new ResourceNotFoundException("发布号格式非法");
            }
            return version;
        } catch (NumberFormatException e) {
            throw new ResourceNotFoundException("发布号格式非法");
        }
    }
}