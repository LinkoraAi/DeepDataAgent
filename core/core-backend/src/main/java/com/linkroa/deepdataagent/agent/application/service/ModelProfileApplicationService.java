package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.query.ListModelProfileQuery;
import com.linkroa.deepdataagent.agent.application.validation.ModelProfileValidator;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.infrastructure.util.ModelCredentialEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 模型配置应用服务（增删改查）
 */
@Service
public class ModelProfileApplicationService {

    @Resource
    private ModelProfileRepository modelProfileRepository;
    @Resource
    private AgentVersionRepository agentVersionRepository;
    @Resource
    private ModelCredentialEncryptionUtil encryptionUtil;
    @Resource
    private TransactionTemplate transactionTemplate;

    public ModelProfile createProfile(CreateModelProfileCommand command) {
        // 名称唯一性校验（数据库唯一索引兜底，业务层先行拦截）
        modelProfileRepository.findByDisplayName(command.displayName())
                .ifPresent(p -> {
                    throw new ResourceConflictException("模型配置名称「" + command.displayName() + "」已被使用");
                });

        String profileId = UUID.randomUUID().toString();
        CredentialResolution credential = resolveCreateCredential(command);
        ModelProfile profile = ModelProfile.create(
                profileId,
                command.displayName(),
                command.description(),
                command.apiFormat(),
                command.apiEndpointUrl(),
                command.modelName(),
                credential.encryptedCredential(),
                credential.secretId(),
                command.modelSeries(),
                command.contextWindowInput(),
                command.contextWindowOutput(),
                command.toolCallRounds(),
                command.modelType(),
                command.vectorDimension()
        );
        return transactionTemplate.execute(status -> modelProfileRepository.save(profile));
    }

    public ModelProfile updateProfile(UpdateModelProfileCommand command) {
        ModelProfile existing = modelProfileRepository.findByProfileId(command.profileId())
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));

        // 名称唯一性校验（排除自身）
        modelProfileRepository.findByDisplayName(command.displayName())
                .filter(p -> !p.profileId().equals(existing.profileId()))
                .ifPresent(p -> {
                    throw new ResourceConflictException("模型配置名称「" + command.displayName() + "」已被使用");
                });

        // 凭证语义：null 保留原值、空串清空、其他值重新加密；secretId 同理并与内嵌凭证互斥
        CredentialResolution credential = resolveUpdateCredential(command, existing);

        ModelProfile updated = ModelProfile.restore(
                existing.profileId(),
                command.displayName(),
                command.description(),
                command.apiFormat(),
                command.apiEndpointUrl(),
                command.modelName(),
                credential.encryptedCredential(),
                credential.secretId(),
                command.modelSeries(),
                command.contextWindowInput(),
                command.contextWindowOutput(),
                command.toolCallRounds(),
                command.modelType(),
                command.vectorDimension(),
                existing.status(),
                existing.createdAt(),
                existing.updatedAt(),
                existing.createdBy(),
                existing.updatedBy()
        );
        return transactionTemplate.execute(status -> modelProfileRepository.update(updated));
    }

    public ModelProfile getProfile(String profileId) {
        return modelProfileRepository.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
    }

    public List<ModelProfile> listProfiles(ListModelProfileQuery query) {
        return modelProfileRepository.findByCondition(query.keyword(), query.status(), query.page(), query.size());
    }

    public long countProfiles(ListModelProfileQuery query) {
        return modelProfileRepository.countByCondition(query.keyword(), query.status());
    }

    public void disableProfile(String profileId) {
        ModelProfile profile = modelProfileRepository.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
        transactionTemplate.executeWithoutResult(status ->
                modelProfileRepository.updateStatus(profileId, ModelProfileStatus.DISABLED));
    }

    public void enableProfile(String profileId) {
        modelProfileRepository.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
        transactionTemplate.executeWithoutResult(status ->
                modelProfileRepository.updateStatus(profileId, ModelProfileStatus.ENABLED));
    }

    public void deleteProfile(String profileId) {
        // 锁行 + 复核引用计数 + 逻辑删除须在同一事务内执行，避免并发下 check-then-act 竞态造成悬空引用
        transactionTemplate.executeWithoutResult(status -> {
            ModelProfile profile = modelProfileRepository.findByProfileIdForUpdate(profileId)
                    .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
            long refCount = agentVersionRepository.countByModelProfileId(profileId);
            ModelProfileValidator.validateDelete(profile, refCount);
            modelProfileRepository.deleteByProfileId(profileId);
        });
    }

    /**
     * 创建场景凭证解析：引用密钥则仅记录 secretId（明文不落库），否则内嵌凭证加密落库。
     */
    private CredentialResolution resolveCreateCredential(CreateModelProfileCommand command) {
        if (StringUtils.isNotBlank(command.credential()) && StringUtils.isNotBlank(command.secretId())) {
            throw new IllegalArgumentException("凭证仅能内嵌或引用密钥其一，不可同时提供");
        }
        if (StringUtils.isNotBlank(command.secretId())) {
            return new CredentialResolution("", command.secretId());
        }
        return new CredentialResolution(encryptionUtil.encrypt(command.credential()), null);
    }

    /**
     * 更新场景凭证解析：null 保留原值、空串清空、其他值替换；内嵌与引用互斥，切换模式时另一方清空。
     */
    private CredentialResolution resolveUpdateCredential(UpdateModelProfileCommand command, ModelProfile existing) {
        String credential = command.credential();
        String secretId = command.secretId();
        if (StringUtils.isNotBlank(credential) && StringUtils.isNotBlank(secretId)) {
            throw new IllegalArgumentException("凭证仅能内嵌或引用密钥其一，不可同时提供");
        }

        String newEncrypted = existing.encryptedCredential();
        String newSecretId = existing.secretId();
        if (StringUtils.isNotBlank(credential)) {
            newEncrypted = encryptionUtil.encrypt(credential);
            newSecretId = "";
        } else if (StringUtils.isNotBlank(secretId)) {
            newSecretId = secretId;
            newEncrypted = "";
        } else {
            if (credential != null && credential.isEmpty()) {
                newEncrypted = "";
            }
            if (secretId != null && secretId.isEmpty()) {
                newSecretId = "";
            }
        }
        return new CredentialResolution(newEncrypted, newSecretId);
    }

    /** 凭证解析结果（内嵌密文与密钥引用引用其一有效）。 */
    private record CredentialResolution(String encryptedCredential, String secretId) {
    }
}