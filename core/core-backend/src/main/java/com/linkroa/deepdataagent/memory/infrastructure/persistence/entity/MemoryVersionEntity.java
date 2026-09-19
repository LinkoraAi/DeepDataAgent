package com.linkroa.deepdataagent.memory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 记忆版本持久化实体（对应 memory_versions 表，不可变快照，含删除墓碑版本）。
 */
@Data
@TableName("memory_versions")
public class MemoryVersionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 版本业务ID（前缀 memver_） */
    private String versionId;
    /** 所属记忆库业务ID */
    private String storeId;
    /** 所属记忆业务ID（mem_ 前缀） */
    private String entryId;
    /** 记忆路径快照 */
    private String entryPath;
    /** 版本号（entry 内从 1 递增） */
    private Integer version;
    /** 动作类型（created/updated/deleted，小写规范值） */
    private String action;
    /** 文本内容（墓碑版本为 null；redact 后置 null） */
    private String content;
    /** 内容字节长度（UTF-8，墓碑版本为 null） */
    private Long size;
    /** 内容 SHA-256 校验值（redact 后置 null） */
    private String contentSha256;
    /** 是否已脱敏 */
    private Boolean redacted;
    /** 脱敏时间 */
    private OffsetDateTime redactedAt;
    /** 版本创建时间 */
    private OffsetDateTime createdAt;
}
