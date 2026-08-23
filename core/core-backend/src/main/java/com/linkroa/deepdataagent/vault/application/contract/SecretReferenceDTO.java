package com.linkroa.deepdataagent.vault.application.contract;

import org.apache.commons.lang3.StringUtils;

/**
 * 凭证密钥解析契约（发布语言 DTO，Published Language）。
 * <p>由 vault BC 在应用边界出版，作为 {@code SecretResolutionPort} 的返回类型，
 * 供 model-profile / runtime 等消费方经 ACL 拿到已解析的密钥明文（仅运行时内存持有，
 * 不落库、不进响应）；本 DTO 强制脱敏 {@code toString}，避免明文随日志 / 异常链输出。</p>
 *
 * @param secretId 密钥业务 ID
 * @param value    解密后的密钥明文
 */
public record SecretReferenceDTO(
        String secretId,
        String value
) {

    /**
     * 紧凑构造器：契约边界校验
     */
    public SecretReferenceDTO {
        if (StringUtils.isBlank(secretId)) {
            throw new IllegalArgumentException("密钥ID不能为空");
        }
    }

    /**
     * 脱敏 toString：明文密钥值不随日志 / 异常链输出。
     */
    @Override
    public String toString() {
        return "SecretReferenceDTO[secretId=" + secretId + ", value=****]";
    }
}