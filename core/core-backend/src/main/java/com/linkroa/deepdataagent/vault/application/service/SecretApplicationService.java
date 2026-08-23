package com.linkroa.deepdataagent.vault.application.service;

import com.linkroa.deepdataagent.agent.api.AgentReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.vault.application.command.CreateSecretCommand;
import com.linkroa.deepdataagent.vault.application.query.ListSecretQuery;
import com.linkroa.deepdataagent.vault.application.validation.SecretValidator;
import com.linkroa.deepdataagent.vault.domain.model.Secret;
import com.linkroa.deepdataagent.vault.domain.repository.SecretRepository;
import com.linkroa.deepdataagent.vault.infrastructure.util.SecretEncryptionUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 密钥应用服务（增删查，明文加密落库）
 */
@Service
public class SecretApplicationService {

    private static final String DEFAULT_WORKSPACE_ID = "default";

    @Resource
    private SecretRepository secretRepository;
    @Resource
    private AgentReferenceApi agentReferenceApi;
    @Resource
    private SecretEncryptionUtil encryptionUtil;
    @Resource
    private TransactionTemplate transactionTemplate;

    public Secret create(CreateSecretCommand command) {
        String encryptedValue = encryptionUtil.encrypt(command.value());
        Secret secret = Secret.create(
                UUID.randomUUID().toString(),
                command.name(),
                encryptedValue,
                DEFAULT_WORKSPACE_ID
        );
        return transactionTemplate.execute(status -> secretRepository.save(secret));
    }

    public List<Secret> list(ListSecretQuery query) {
        return secretRepository.findByPage(query.page(), query.size());
    }

    public long count() {
        return secretRepository.countAll();
    }

    public Secret get(String secretId) {
        return secretRepository.findBySecretId(secretId)
                .orElseThrow(() -> new ResourceNotFoundException("密钥不存在"));
    }

    public void delete(String secretId) {
        transactionTemplate.executeWithoutResult(status -> {
            Secret secret = secretRepository.findBySecretIdForUpdate(secretId)
                    .orElseThrow(() -> new ResourceNotFoundException("密钥不存在"));
            long refCount = agentReferenceApi.countSecretReferences(secretId);
            SecretValidator.validateDelete(secret, refCount);
            secretRepository.deleteBySecretId(secretId);
        });
    }
}