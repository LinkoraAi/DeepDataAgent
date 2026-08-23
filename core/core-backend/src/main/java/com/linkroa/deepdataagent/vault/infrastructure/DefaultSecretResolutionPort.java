package com.linkroa.deepdataagent.vault.infrastructure;

import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.vault.application.contract.SecretReferenceDTO;
import com.linkroa.deepdataagent.vault.application.port.SecretResolutionPort;
import com.linkroa.deepdataagent.vault.domain.model.Secret;
import com.linkroa.deepdataagent.vault.domain.repository.SecretRepository;
import com.linkroa.deepdataagent.vault.infrastructure.util.SecretEncryptionUtil;
import org.springframework.stereotype.Component;

/**
 * 凭证密钥解析端口实现：复用 vault 独立密钥的 AES/GCM 方案解密，明文仅内存持有。
 */
@Component
public class DefaultSecretResolutionPort implements SecretResolutionPort {

    private final SecretRepository secretRepository;
    private final SecretEncryptionUtil encryptionUtil;

    public DefaultSecretResolutionPort(SecretRepository secretRepository, SecretEncryptionUtil encryptionUtil) {
        this.secretRepository = secretRepository;
        this.encryptionUtil = encryptionUtil;
    }

    @Override
    public SecretReferenceDTO resolve(String secretId) {
        Secret secret = secretRepository.findBySecretId(secretId)
                .orElseThrow(() -> new ResourceNotFoundException("密钥不存在"));
        String plaintext = encryptionUtil.decrypt(secret.encryptedValue());
        return new SecretReferenceDTO(secret.secretId(), plaintext);
    }
}