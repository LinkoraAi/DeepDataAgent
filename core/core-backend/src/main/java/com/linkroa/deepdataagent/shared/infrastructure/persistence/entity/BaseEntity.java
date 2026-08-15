package com.linkroa.deepdataagent.shared.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 持久化实体基类：统一主键与基础字段。
 * <p>created_at / updated_at / created_by / updated_by / is_deleted 由
 * {@code MybatisPlusMetaObjectHandler} 在 INSERT / UPDATE 时自动填充，
 * 业务代码不手工维护；is_deleted 走 MyBatis-Plus 逻辑删除。</p>
 * <p>与建表约定对齐：时间列统一为 TIMESTAMPTZ（Java 侧使用 {@link OffsetDateTime}，
 * 统一按中国时区 Asia/Shanghai 写入与读取），字段命名与全库基础字段规范保持一致。</p>
 */
@Data
public abstract class BaseEntity {

    /** 主键（数据库自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 创建时间（插入时自动填充，TIMESTAMPTZ） */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    /** 更新时间（插入/更新时自动填充，TIMESTAMPTZ） */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    /** 创建人（插入时自动填充） */
    @TableField(value = "created_by", fill = FieldFill.INSERT)
    private String createdBy;

    /** 更新人（插入/更新时自动填充） */
    @TableField(value = "updated_by", fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /** 删除标记（逻辑删除：0=未删除，1=已删除） */
    @TableLogic
    @TableField(value = "is_deleted", fill = FieldFill.INSERT)
    private Integer isDeleted;
}