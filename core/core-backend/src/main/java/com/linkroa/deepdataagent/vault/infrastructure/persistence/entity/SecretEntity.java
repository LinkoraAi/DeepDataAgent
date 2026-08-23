package com.linkroa.deepdataagent.vault.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 凭证密钥持久化实体（对应 vault_secret 表）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vault_secret")
public class SecretEntity extends BaseEntity {

    /** 密钥业务ID */
    private String secretId;
    /** 密钥名称 */
    private String name;
    /** 加密后的密钥值（AES/GCM） */
    private String encryptedValue;
    /** 工作空间ID（占位） */
    private String workspaceId;
}