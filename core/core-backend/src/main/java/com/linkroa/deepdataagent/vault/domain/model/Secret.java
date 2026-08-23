package com.linkroa.deepdataagent.vault.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 凭证密钥领域模型（对应 vault_secret 表）
 *
 * @param id             数据库主键
 * @param secretId       密钥业务唯一ID
 * @param name           名称（≤255字符）
 * @param encryptedValue 加密后的密钥值（AES/GCM，独立密钥；明文永不落库）
 * @param workspaceId    工作空间归属（本期占位，默认值兜底，不做边界校验）
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 * @param createdBy      创建人
 * @param updatedBy      更新人
 */
public record Secret(
        Long id,
        String secretId,
        String name,
        String encryptedValue,
        String workspaceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_\\-]{0,254}$");

    /**
     * 紧凑构造器：不变量校验
     */
    public Secret {
        if (StringUtils.isBlank(secretId)) {
            throw new IllegalArgumentException("密钥ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("密钥名称不能为空");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("密钥名称长度不能超过255个字符");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("密钥名称只能包含中文、英文字母、数字、下划线和连字符，且不能以数字或特殊字符开头");
        }
        if (StringUtils.isBlank(encryptedValue)) {
            throw new IllegalArgumentException("密钥值不能为空");
        }
    }

    /**
     * 创建新的密钥（密文传入；明文加密在应用层完成）
     */
    public static Secret create(String secretId, String name, String encryptedValue, String workspaceId) {
        return new Secret(
                null, secretId, name, encryptedValue, workspaceId,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static Secret restore(
            Long id,
            String secretId,
            String name,
            String encryptedValue,
            String workspaceId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new Secret(id, secretId, name, encryptedValue, workspaceId, createdAt, updatedAt, createdBy, updatedBy);
    }
}