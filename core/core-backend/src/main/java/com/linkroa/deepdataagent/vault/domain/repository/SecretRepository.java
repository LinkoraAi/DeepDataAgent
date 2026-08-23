package com.linkroa.deepdataagent.vault.domain.repository;

import com.linkroa.deepdataagent.vault.domain.model.Secret;

import java.util.List;
import java.util.Optional;

/**
 * 凭证密钥仓储接口
 */
public interface SecretRepository {

    /**
     * 保存密钥（新增，密文落库）
     */
    Secret save(Secret secret);

    /**
     * 按业务ID查询
     */
    Optional<Secret> findBySecretId(String secretId);

    /**
     * 按业务ID查询并锁定该行（FOR UPDATE），用于删除等 check-then-act 场景的事务内串行化
     */
    Optional<Secret> findBySecretIdForUpdate(String secretId);

    /**
     * 批量按业务ID查询（供解析 / 引用完整性校验）
     */
    List<Secret> findByIds(List<String> secretIds);

    /**
     * 分页查询
     */
    List<Secret> findByPage(int page, int size);

    /**
     * 分页统计
     */
    long countAll();

    /**
     * 逻辑删除
     */
    void deleteBySecretId(String secretId);
}