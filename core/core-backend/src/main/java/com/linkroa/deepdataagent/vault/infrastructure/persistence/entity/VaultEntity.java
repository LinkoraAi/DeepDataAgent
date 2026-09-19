package com.linkroa.deepdataagent.vault.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 保管库持久化实体（对应 vaults 表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vaults")
public class VaultEntity extends BaseEntity {

    /** 保管库业务ID（前缀 vault_） */
    private String vaultId;
    /** 显示名称 */
    private String displayName;
    /** 元数据 JSON（JSONB，可选；列名 metadata_json 与领域字段 metadata 不一致，需显式绑定） */
    @TableField(value = "metadata_json", typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;
    /** 归属用户 ID */
    private Long ownerId;
    /** 归档时间（软删，NULL=未归档） */
    private OffsetDateTime archivedAt;
}