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
import com.linkroa.deepdataagent.shared.security.AuthContext;
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

    /**
     * 创建模型配置：display_name 全局唯一校验通过后，凭证明文经独立密钥 AES-GCM 加密落库（明文不落库、不进响应）。
     *
     * @param command 创建命令
     * @return 新建模型配置
     * @throws ResourceConflictException display_name 已被占用
     */
    public ModelProfile createProfile(CreateModelProfileCommand command) {
        Long ownerId = AuthContext.requireUserId();
        // 名称唯一性校验（数据库唯一索引兜底，业务层先行拦截；display_name 全局唯一）
        modelProfileRepository.findByDisplayName(command.displayName())
                .ifPresent(p -> {
                    throw new ResourceConflictException("模型配置名称「" + command.displayName() + "」已被使用");
                });

        String profileId = UUID.randomUUID().toString();
        // 凭证内嵌加密单一模式：明文经独立密钥 AES-GCM 加密落库，明文不落库
        String encryptedCredential = encryptionUtil.encrypt(command.credential());
        ModelProfile profile = ModelProfile.create(
                profileId,
                command.displayName(),
                command.description(),
                command.apiFormat(),
                command.apiEndpointUrl(),
                command.modelName(),
                encryptedCredential,
                command.modelSeries(),
                command.contextWindowInput(),
                command.contextWindowOutput(),
                command.toolCallRounds(),
                command.modelType(),
                command.vectorDimension(),
                ownerId
        );
        return transactionTemplate.execute(status -> modelProfileRepository.save(profile));
    }

    /**
     * 更新模型配置：名称唯一性（排除自身）与凭证语义（{@code null} 保留原值 / 空串清空 / 其他重新加密）处理后落库。
     *
     * @param command 更新命令
     * @return 更新后的模型配置
     * @throws ResourceNotFoundException 配置不存在或不属于当前用户
     * @throws ResourceConflictException display_name 已被其他配置占用
     */
    public ModelProfile updateProfile(UpdateModelProfileCommand command) {
        ModelProfile existing = requireOwned(command.profileId());

        // 名称唯一性校验（排除自身）
        modelProfileRepository.findByDisplayName(command.displayName())
                .filter(p -> !p.profileId().equals(existing.profileId()))
                .ifPresent(p -> {
                    throw new ResourceConflictException("模型配置名称「" + command.displayName() + "」已被使用");
                });

        // 凭证语义：null 保留原值、空串清空、其他值重新加密（内嵌加密单一模式）
        String encryptedCredential = resolveUpdateCredential(command.credential(), existing.encryptedCredential());

        ModelProfile updated = ModelProfile.restore(
                existing.profileId(),
                command.displayName(),
                command.description(),
                command.apiFormat(),
                command.apiEndpointUrl(),
                command.modelName(),
                encryptedCredential,
                command.modelSeries(),
                command.contextWindowInput(),
                command.contextWindowOutput(),
                command.toolCallRounds(),
                command.modelType(),
                command.vectorDimension(),
                existing.status(),
                existing.ownerId(),
                existing.createdAt(),
                existing.updatedAt(),
                existing.createdBy(),
                existing.updatedBy()
        );
        return transactionTemplate.execute(status -> modelProfileRepository.update(updated));
    }

    /**
     * 按业务 ID 读取模型配置（owner 隔离，不存在或越权一律 404）。
     *
     * @param profileId 模型配置业务 ID
     * @return 模型配置
     * @throws ResourceNotFoundException 配置不存在或不属于当前用户
     */
    public ModelProfile getProfile(String profileId) {
        return requireOwned(profileId);
    }

    /**
     * 按关键字 / 状态分页列出当前用户的模型配置。
     *
     * @param query 查询条件（关键字 / 状态 / 页码 / 页大小）
     * @return 当前用户可见的模型配置列表
     */
    public List<ModelProfile> listProfiles(ListModelProfileQuery query) {
        Long ownerId = AuthContext.requireUserId();
        return modelProfileRepository.findByCondition(ownerId, query.keyword(), query.status(), query.page(), query.size());
    }

    /**
     * 统计当前用户满足条件的模型配置总数（与分页列表同口径，供分页总数展示）。
     *
     * @param query 查询条件（关键字 / 状态）
     * @return 命中总数
     */
    public long countProfiles(ListModelProfileQuery query) {
        Long ownerId = AuthContext.requireUserId();
        return modelProfileRepository.countByCondition(ownerId, query.keyword(), query.status());
    }

    /**
     * 停用模型配置：仅做 owner 存在性校验后在事务内将状态置 {@code DISABLED}。
     *
     * @param profileId 模型配置业务 ID
     * @throws ResourceNotFoundException 配置不存在或不属于当前用户
     */
    public void disableProfile(String profileId) {
        // 仅做存在性校验（与 enableProfile 一致），更新状态不依赖实例本身
        requireOwned(profileId);
        transactionTemplate.executeWithoutResult(status ->
                modelProfileRepository.updateStatus(profileId, ModelProfileStatus.DISABLED));
    }

    /**
     * 启用模型配置：仅做 owner 存在性校验后在事务内将状态置 {@code ENABLED}。
     *
     * @param profileId 模型配置业务 ID
     * @throws ResourceNotFoundException 配置不存在或不属于当前用户
     */
    public void enableProfile(String profileId) {
        requireOwned(profileId);
        transactionTemplate.executeWithoutResult(status ->
                modelProfileRepository.updateStatus(profileId, ModelProfileStatus.ENABLED));
    }

    /**
     * 删除模型配置：锁行 + 复核 Agent 版本引用计数 + 逻辑删除须在同一事务内完成，避免并发 check-then-act 造成悬空引用。
     *
     * @param profileId 模型配置业务 ID
     * @throws ResourceNotFoundException 配置不存在或不属于当前用户
     * @throws ResourceConflictException 仍被 Agent 版本引用
     */
    public void deleteProfile(String profileId) {
        // 锁行 + 复核引用计数 + 逻辑删除须在同一事务内执行，避免并发下 check-then-act 竞态造成悬空引用
        transactionTemplate.executeWithoutResult(status -> {
            ModelProfile profile = requireOwnedForUpdate(profileId);
            long refCount = agentVersionRepository.countByModelProfileId(profileId);
            ModelProfileValidator.validateDelete(profile, refCount);
            modelProfileRepository.deleteByProfileId(profileId);
        });
    }

    /**
     * 更新场景凭证解析：null 保留原值、空串清空、其他值重新加密（内嵌加密单一模式）。
     *
     * @param credential          请求体中的凭证明文（null=保留 / 空串=清空 / 其他=更新）
     * @param existingEncrypted   既有密文
     */
    private String resolveUpdateCredential(String credential, String existingEncrypted) {
        if (StringUtils.isBlank(credential)) {
            return credential != null && credential.isEmpty() ? "" : existingEncrypted;
        }
        return encryptionUtil.encrypt(credential);
    }

    /**
     * 校验模型配置归属：仅 owner 可见（不含则 404）。
     */
    private ModelProfile requireOwned(String profileId) {
        ModelProfile profile = modelProfileRepository.findByProfileId(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
        if (!profile.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("模型配置不存在");
        }
        return profile;
    }

    /**
     * 校验模型配置归属并锁行。
     */
    private ModelProfile requireOwnedForUpdate(String profileId) {
        ModelProfile profile = modelProfileRepository.findByProfileIdForUpdate(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("模型配置不存在"));
        if (!profile.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("模型配置不存在");
        }
        return profile;
    }
}