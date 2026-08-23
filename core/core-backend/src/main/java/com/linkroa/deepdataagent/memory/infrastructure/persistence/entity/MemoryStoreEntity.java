package com.linkroa.deepdataagent.memory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 记忆库持久化实体（对应 memory_store 表）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("memory_store")
public class MemoryStoreEntity extends BaseEntity {

    /** 记忆库业务ID */
    private String memoryId;
    /** 记忆库名称 */
    private String name;
    /** 记忆类型（SHORT_TERM / LONG_TERM） */
    private String type;
    /** 工作空间ID（占位） */
    private String workspaceId;
}