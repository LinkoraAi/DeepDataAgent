package com.linkroa.deepdataagent.memory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 记忆条目持久化实体（对应 memories 表，store_id + path 活跃唯一，tombstone 软删）。
 * <p>非 BaseEntity：本表无 is_deleted 逻辑删列，删除语义为 {@code deleted_at} 墓碑。</p>
 */
@Data
@TableName("memories")
public class MemoryEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 记忆业务ID（前缀 mem_） */
    private String memoryId;
    /** 所属记忆库业务ID */
    private String storeId;
    /** 记忆路径（相对，不以 / 开头） */
    private String path;
    /** 当前版本号（OCC 乐观并发） */
    private Integer version;
    /** 当前内容字节长度（UTF-8） */
    private Long size;
    /** 当前内容 SHA-256 校验值（hex，64 字符） */
    private String contentSha256;
    /** 自定义元数据（JSONB 文本，对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;
    /** 删除时间（tombstone 软删，null=活跃） */
    private OffsetDateTime deletedAt;
    /** 创建时间 */
    private OffsetDateTime createdAt;
    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
