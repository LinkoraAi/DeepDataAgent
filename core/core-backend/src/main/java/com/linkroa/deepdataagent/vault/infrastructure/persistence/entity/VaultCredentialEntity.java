package com.linkroa.deepdataagent.vault.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 凭证持久化实体（对应 vault_credentials 表）。
 * <p>密文以 BYTEA 字节数组承载（AES/GCM），明文永不落库、不进响应。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vault_credentials")
public class VaultCredentialEntity extends BaseEntity {

    /** 凭证业务唯一ID（前缀 cr_） */
    private String credentialId;
    /** 所属保管库业务ID */
    private String vaultId;
    /** 凭证鉴权类型（值域 static_bearer / mcp_oauth，由领域枚举校验） */
    private String authType;
    /** 绑定的 MCP 服务器 URL（运行时按此匹配注入鉴权） */
    private String mcpServerUrl;
    /** AES/GCM 加密后的密文（BYTEA；mcp_oauth 为整包加密的秘密材料信封） */
    private byte[] ciphertext;
    /** 访问令牌到期时间（非密列；显式清除为 null 必须参与更新，故强制 ALWAYS） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private OffsetDateTime expiresAt;
    /** 元数据 JSON（JSONB，可选；列名 metadata_json 与领域字段 metadata 不一致，需显式绑定） */
    @TableField(value = "metadata_json", typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;
    /** 归档时间（软删，NULL=未归档；归档不再用于新 Session 挂载） */
    private OffsetDateTime archivedAt;
}