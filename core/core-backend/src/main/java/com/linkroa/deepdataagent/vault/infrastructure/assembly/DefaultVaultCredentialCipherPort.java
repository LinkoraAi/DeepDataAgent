package com.linkroa.deepdataagent.vault.infrastructure.assembly;

import com.linkroa.deepdataagent.vault.application.port.VaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.infrastructure.util.VaultCredentialEncryptionUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 凭证加解密出站端口实现（{@link VaultCredentialCipherPort}）：委托
 * {@link VaultCredentialEncryptionUtil} 完成 AES-GCM 加解密，密钥与算法细节
 * 收敛在基础设施层，应用服务不感知。
 */
@Component
public class DefaultVaultCredentialCipherPort implements VaultCredentialCipherPort {

    @Resource
    private VaultCredentialEncryptionUtil encryptionUtil;

    @Override
    public byte[] encrypt(String plaintext) {
        return encryptionUtil.encrypt(plaintext);
    }

    @Override
    public String decrypt(byte[] ciphertext) {
        return encryptionUtil.decrypt(ciphertext);
    }
}