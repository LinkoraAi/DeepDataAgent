package com.linkroa.deepdataagent.skill.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 技能壳持久化实体（对应 {@code skill} 表）。
 * <p>壳对象承载展示名（创建后不可改）、来源与最新版本指针；内容版本见
 * {@link SkillContentVersionEntity}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill")
public class SkillEntity extends BaseEntity {

    /** 技能业务 ID（前缀 skill_） */
    private String skillId;
    /** 技能展示名（≤255，创建后不可改） */
    private String displayTitle;
    /** 技能来源（catalog / custom） */
    private String source;
    /** 最新版本键（epoch 微秒字符串；全部版本删除后为 null） */
    private String latestVersion;
    /** 业务自定义元数据（JSONB 键值对象，缺省 {}） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;
    /** 归属用户 ID */
    private Long ownerId;
}