package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateDeploymentCommand;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.Deployment;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRepository;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 部署应用服务（激活 / 回滚目标版本，写入审计记录，更新 active_version）
 */
@Service
public class DeploymentApplicationService {

    @Resource
    private DeploymentRepository deploymentRepository;
    @Resource
    private AgentDefinitionRepository agentDefinitionRepository;
    @Resource
    private AgentVersionRepository agentVersionRepository;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 部署（激活 / 回滚）目标版本：校验版本存在后写入部署审计记录，
     * 并将 Agent 的 active_version 置为目标版本（latest_version 保持不变）。
     */
    public Deployment deploy(CreateDeploymentCommand command) {
        return transactionTemplate.execute(status -> {
            AgentDefinition definition = agentDefinitionRepository.findByAgentIdForUpdate(command.agentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
            agentVersionRepository.findByAgentIdAndVersionNumber(command.agentId(), command.versionNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("Agent版本不存在"));

            Deployment deployment = deploymentRepository.save(Deployment.create(
                    UUID.randomUUID().toString(),
                    command.agentId(),
                    command.versionNumber(),
                    definition.workspaceId()
            ));
            agentDefinitionRepository.updateActiveVersion(command.agentId(), command.versionNumber());
            return deployment;
        });
    }

    public List<Deployment> list(String agentId) {
        agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        return deploymentRepository.listByAgentId(agentId);
    }

    public Deployment get(String deploymentId) {
        return deploymentRepository.findByDeploymentId(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("部署记录不存在"));
    }
}