package com.linkroa.deepdataagent.skill.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 技能内容版本持久化实体（对应 {@code skill_content_version} 表，不可变快照元数据）。
 * <p>技能包（SKILL.md 正文 + 资源文件）落对象存储资产目录，本表仅存 frontmatter 派生元数据
 * 与内部完整性校验值。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_content_version")
public class SkillContentVersionEntity extends BaseEntity {

    /** 版本业务 ID（前缀 skillver_） */
    private String versionId;
    /** 所属技能业务 ID */
    private String skillId;
    /** 版本键（创建时刻 epoch 微秒字符串） */
    private String version;
    /** frontmatter name（等于包顶级目录名） */
    private String name;
    /** frontmatter description */
    private String description;
    /** 包顶级目录名（恒等于 name） */
    private String directory;
    /** 技能包内容 SHA-256 校验值 */
    private String contentSha256;
    /** 技能包内容字节长度 */
    private Long contentSize;
    /** 资源清单摘要（JSONB：相对路径 → 字节长度，可空） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String resources;
}