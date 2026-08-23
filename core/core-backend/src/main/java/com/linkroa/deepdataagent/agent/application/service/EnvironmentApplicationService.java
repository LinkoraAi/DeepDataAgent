package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListEnvironmentQuery;
import com.linkroa.deepdataagent.agent.application.validation.EnvironmentValidator;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 运行环境应用服务（增删改查）
 */
@Service
public class EnvironmentApplicationService {

    private static final String DEFAULT_WORKSPACE_ID = "default";

    @Resource
    private EnvironmentRepository environmentRepository;
    @Resource
    private AgentVersionRepository agentVersionRepository;
    @Resource
    private TransactionTemplate transactionTemplate;

    public Environment create(CreateEnvironmentCommand command) {
        validateNameUnique(null, command.name());
        Environment environment = Environment.create(
                UUID.randomUUID().toString(),
                command.name(),
                command.type(),
                command.sandboxSpec(),
                DEFAULT_WORKSPACE_ID
        );
        return transactionTemplate.execute(status -> environmentRepository.save(environment));
    }

    public List<Environment> list(ListEnvironmentQuery query) {
        return environmentRepository.findByPage(query.page(), query.size());
    }

    public long count() {
        return environmentRepository.countAll();
    }

    public Environment get(String environmentId) {
        return environmentRepository.findByEnvironmentId(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("运行环境不存在"));
    }

    public Environment update(UpdateEnvironmentCommand command) {
        Environment existing = environmentRepository.findByEnvironmentId(command.environmentId())
                .orElseThrow(() -> new ResourceNotFoundException("运行环境不存在"));
        validateNameUnique(command.environmentId(), command.name());
        Environment updated = Environment.restore(
                existing.id(),
                command.environmentId(),
                command.name(),
                command.type(),
                command.sandboxSpec(),
                existing.workspaceId(),
                existing.createdAt(),
                existing.updatedAt(),
                existing.createdBy(),
                existing.updatedBy()
        );
        return transactionTemplate.execute(status -> environmentRepository.update(updated));
    }

    /**
     * 校验运行环境名称唯一性：排除自身（excludeEnvironmentId）后仍存在同名环境视为冲突。
     *
     * @param excludeEnvironmentId 排除的环境业务 ID（更新场景传自身 ID，创建场景传 null）
     * @param name                 待校验名称
     */
    private void validateNameUnique(String excludeEnvironmentId, String name) {
        environmentRepository.findByName(name)
                .filter(existing -> !existing.environmentId().equals(excludeEnvironmentId))
                .ifPresent(existing -> {
                    throw new ResourceConflictException("运行环境名称「" + name + "」已被使用");
                });
    }

    public void delete(String environmentId) {
        transactionTemplate.executeWithoutResult(status -> {
            Environment environment = environmentRepository.findByEnvironmentIdForUpdate(environmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("运行环境不存在"));
            long refCount = agentVersionRepository.countByEnvironmentId(environmentId);
            EnvironmentValidator.validateDelete(environment, refCount);
            environmentRepository.deleteByEnvironmentId(environmentId);
        });
    }
}