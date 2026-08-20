package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.application.query.ListAgentQuery;
import com.linkroa.deepdataagent.agent.application.validation.AgentValidator;
import com.linkroa.deepdataagent.agent.application.validation.ModelProfileValidator;
import com.linkroa.deepdataagent.agent.application.validation.SkillMountValidator;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.domain.repository.SkillRepository;
import com.linkroa.deepdataagent.agent.domain.service.AgentVersionDomainService;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Agent 定义与版本应用服务（创建 / 发布 / 归档 / 删除 / 查询）
 */
@Service
public class AgentApplicationService {

    @Resource
    private AgentDefinitionRepository agentDefinitionRepository;
    @Resource
    private AgentVersionRepository agentVersionRepository;
    @Resource
    private ModelProfileRepository modelProfileRepository;
    @Resource
    private SkillRepository skillRepository;
    @Resource
    private AgentVersionDomainService versionDomainService;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 创建 Agent：写入 definition + 生成发布号为 1 的初始版本快照，latest_version 置 1。
     * 整个流程在同一事务内完成，失败不产生任何数据变更。
     */
    public AgentDefinition createAgent(CreateAgentCommand command) {
        // 名称唯一性校验（数据库唯一索引兜底，业务层先行拦截）
        agentDefinitionRepository.findByName(command.name())
                .ifPresent(a -> {
                    throw new ResourceConflictException("Agent名称「" + command.name() + "」已被使用");
                });
        // 引用的模型配置须存在且为 ENABLED
        ModelProfile profile = resolveReferableProfile(command.modelProfileId());
        // 挂载的技能引用（skillId + version）须存在
        SkillMountValidator.validateReferable(command.skillIds(), skillRepository);

        String agentId = UUID.randomUUID().toString();
        return transactionTemplate.execute(status -> {
            AgentDefinition definition = agentDefinitionRepository.save(AgentDefinition.create(agentId, command.name(), command.description()));
            AgentVersion v1 = publishVersionInTransaction(definition, command.name(), command.description(), command.system(),
                    profile.profileId(), command.skillIds(),
                    command.knowledgeBaseIds(), command.dataSourceIds());
            return agentDefinitionRepository.update(withLatestVersion(definition, v1.versionNumber()));
        });
    }

    /**
     * 发布新版本（全量替换）：事务内对 definition 行加锁串行化版本号计算，
     * 新版本号 = MAX(version_number)+1，latest_version 与版本行同源于同一次计算。
     */
    public AgentVersion publishVersion(PublishAgentVersionCommand command) {
        // 引用的模型配置须存在且为 ENABLED
        ModelProfile profile = resolveReferableProfile(command.modelProfileId());
        // 挂载的技能引用（skillId + version）须存在
        SkillMountValidator.validateReferable(command.skillIds(), skillRepository);

        return transactionTemplate.execute(status -> {
            AgentDefinition definition = agentDefinitionRepository.findByAgentIdForUpdate(command.agentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
            // 归档后拒绝发布新版本
            AgentValidator.validatePublishable(definition);
            AgentVersion version = publishVersionInTransaction(definition, command.name(), command.description(),
                    command.system(), profile.profileId(), command.skillIds(),
                    command.knowledgeBaseIds(), command.dataSourceIds());
            agentDefinitionRepository.update(withLatestVersion(definition, version.versionNumber()));
            return version;
        });
    }

    /**
     * 事务内发布一个版本快照（共用行锁，保证 MAX+1 计算串行且 latest_version 与版本号一致）
     */
    private AgentVersion publishVersionInTransaction(AgentDefinition definition, String name, String description,
                                                     String system, String modelProfileId,
                                                     String skillIds, String knowledgeBaseIds, String dataSourceIds) {
        int maxVersion = agentVersionRepository.findMaxVersionNumber(definition.agentId());
        int nextVersion = versionDomainService.nextVersionNumber(maxVersion);
        AgentVersion version = AgentVersion.create(
                UUID.randomUUID().toString(),
                definition.agentId(),
                nextVersion,
                name != null ? name : definition.name(),
                description,
                system,
                modelProfileId,
                skillIds,
                knowledgeBaseIds,
                dataSourceIds
        );
        return agentVersionRepository.save(version);
    }

    public AgentDefinition getAgent(String agentId) {
        return agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
    }

    /**
     * 查询某 Agent 的最新版本快照（详情用；无版本时返回 null）
     */
    public AgentVersion getLatestVersion(String agentId) {
        int latestVersion = getAgent(agentId).latestVersion();
        if (latestVersion < 1) {
            return null;
        }
        return agentVersionRepository.findByAgentIdAndVersionNumber(agentId, latestVersion).orElse(null);
    }

    public List<AgentDefinition> listAgents(ListAgentQuery query) {
        return agentDefinitionRepository.findByCondition(query.keyword(), query.includeArchived(), query.page(), query.size());
    }

    public long countAgents(ListAgentQuery query) {
        return agentDefinitionRepository.countByCondition(query.keyword(), query.includeArchived());
    }

    public void archiveAgent(String agentId) {
        agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        transactionTemplate.executeWithoutResult(status -> agentDefinitionRepository.updateArchived(agentId, true));
    }

    public void unarchiveAgent(String agentId) {
        agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        transactionTemplate.executeWithoutResult(status -> agentDefinitionRepository.updateArchived(agentId, false));
    }

    public void deleteAgent(String agentId) {
        agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        transactionTemplate.executeWithoutResult(status -> {
            agentDefinitionRepository.deleteByAgentId(agentId);
            agentVersionRepository.deleteByAgentId(agentId);
        });
    }

    /**
     * 查询某 Agent 的版本列表（按发布号倒序，最新在前）
     */
    public List<AgentVersion> listVersions(String agentId) {
        agentDefinitionRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent不存在"));
        return agentVersionRepository.listByAgentId(agentId);
    }

    /**
     * 校验并解析可引用的模型配置（存在且 ENABLED），不存在 → 404
     */
    private ModelProfile resolveReferableProfile(String modelProfileId) {
        ModelProfile profile = modelProfileRepository.findByProfileId(modelProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
        ModelProfileValidator.validateReferable(profile);
        return profile;
    }

    /**
     * 复制 definition 并更新 latest_version
     */
    private AgentDefinition withLatestVersion(AgentDefinition definition, int latestVersion) {
        return AgentDefinition.restore(
                definition.id(),
                definition.agentId(),
                definition.name(),
                definition.description(),
                definition.archived(),
                definition.archivedAt(),
                latestVersion,
                definition.createdAt(),
                definition.updatedAt(),
                definition.createdBy(),
                definition.updatedBy()
        );
    }
}