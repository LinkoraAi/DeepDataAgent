package com.linkroa.deepdataagent.skill.application.port;

import com.linkroa.deepdataagent.skill.domain.model.SkillContent;

/**
 * 技能资产内容存取端口（进程内出站端口，依赖倒置）。
 * <p>技能内容（SKILL.md 原文 + 资源文件）落对象存储的
 * {@code skills/<skill_id>/<epoch>/} 前缀，数据库仅存 frontmatter 派生元数据与内部完整性
 * 校验值（见 {@code skill_content_version} 表）。实现由 skill 基础设施
 * {@code DefaultSkillAssetPort} 经 shared 对象存储端口提供；版本键生成与 SHA-256 校验由应用服务
 * 负责，本端口只做字节存取。调用方必须在事务内先抢得版本键（台账唯一索引裁决）后才调用
 * {@link #write}，以保证不会清空他人版本前缀。</p>
 */
public interface SkillAssetPort {

    /**
     * 写入指定技能版本的完整内容（同版本先清前缀再全量重写，仅补偿 / 重放路径触发）。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键（epoch 微秒字符串，调用方须已在台账唯一约束下抢得）
     * @param content 技能内容（SKILL.md 原文 + 资源）
     */
    void write(String skillId, String version, SkillContent content);

    /**
     * 读取指定技能版本的内容。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键
     * @return 技能内容；正文对象缺失返回 {@code null}（资源缺失视为损坏并显式失败）
     */
    SkillContent read(String skillId, String version);
}