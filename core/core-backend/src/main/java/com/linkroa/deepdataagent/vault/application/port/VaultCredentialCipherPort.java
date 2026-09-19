package com.linkroa.deepdataagent.vault.application.port;

/**
 * 凭证加解密出站端口（进程内依赖倒置）。
 * <p>应用服务只依赖本端口完成 AES-GCM 加解密，不直接触碰基础设施实现
 * （密钥由基础设施从 {@code vault.encryption.key} 派生）；明文仅在该端口
 * 调用栈内存中存在，不落库、不进响应与日志。</p>
 */
public interface VaultCredentialCipherPort {

    /**
     * 加密明文并返回 {@code [IV || 密文]} 字节（空串 / 空值原样透传，
     * 由上游命令不变量保证凭证明文非空）。
     *
     * @param plaintext 明文（UTF-8）
     * @return 密文字节数组（BYTEA 落库载荷）
     */
    byte[] encrypt(String plaintext);

    /**
     * 解密密文字节为明文（空 / 非法长度原样透传）。
     *
     * @param ciphertext {@code [IV || 密文]} 字节（来自 BYTEA 列）
     * @return 解密后的明文（UTF-8）
     */
    String decrypt(byte[] ciphertext);
}