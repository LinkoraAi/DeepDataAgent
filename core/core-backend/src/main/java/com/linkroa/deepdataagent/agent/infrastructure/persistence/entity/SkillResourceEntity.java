package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 技能资源持久化实体（对应 skill_resource 表，每版本一行）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_resource")
public class SkillResourceEntity extends BaseEntity {

    /** 技能业务ID */
    private String skillId;
    /** 发布版本号 */
    private Integer versionNumber;
    /** 技能名称 */
    private String name;
    /** 技能描述 */
    private String description;
    /** 技能类型（1=自定义 2=官方预留） */
    private Integer skillType;
    /** 存储类型（LOCAL_FILE / OSS预留） */
    private String storageType;
    /** 存储路径（本地相对路径或对象存储key） */
    private String storageKey;
    /** 内容SHA256校验 */
    private String contentSha256;
    /** 内容大小（字节） */
    private Long contentSize;
    /** 状态（ACTIVE / 预留 CHECKING/REJECTED） */
    private String status;
}