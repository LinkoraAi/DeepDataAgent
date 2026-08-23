package com.linkroa.deepdataagent.vault.application.validation;

import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.vault.domain.model.Secret;

/**
 * 密钥应用级校验器
 */
public class SecretValidator {

    /**
     * 校验删除冲突：仍被未删除的模型配置引用时不可删除
     *
     * @param secret   目标密钥
     * @param refCount 引用数量
     */
    public static void validateDelete(Secret secret, long refCount) {
        if (refCount > 0) {
            throw new ResourceConflictException("密钥「" + secret.name()
                    + "」仍被 " + refCount + " 个模型配置引用，无法删除");
        }
    }
}