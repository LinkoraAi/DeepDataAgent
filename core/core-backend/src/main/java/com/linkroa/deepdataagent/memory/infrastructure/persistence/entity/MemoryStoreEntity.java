package com.linkroa.deepdataagent.memory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 记忆库持久化实体（对应 memory_stores 表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("memory_stores")
public class MemoryStoreEntity extends BaseEntity {

    /** 记忆库业务ID（前缀 ms_） */
    private String storeId;
    /** 记忆库名称 */
    private String name;
    /** 记忆库描述（缺省空串） */
    private String description;
    /** 状态（active/archived，小写规范值） */
    private String status;
    /** 活跃记忆条目数 */
    private Integer entryCount;
    /** 活跃记忆内容总字节数 */
    private Long totalSize;
    /** 归属用户 ID */
    private Long ownerId;
    /** 归档时间（null=未归档） */
    private OffsetDateTime archivedAt;
}
