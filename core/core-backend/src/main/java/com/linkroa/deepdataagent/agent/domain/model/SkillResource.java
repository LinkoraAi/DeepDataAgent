package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 技能资源领域模型（对应 skill_resource 表，每版本一行）
 *
 * @param id            数据库主键
 * @param skillId       技能业务唯一ID
 * @param versionNumber 发布版本号（同一技能内 MAX+1）
 * @param name          技能名称
 * @param description   技能描述
 * @param skillType     技能类型
 * @param storageType   存储类型
 * @param storageKey    存储路径（本地相对路径或对象存储key）
 * @param contentSha256 内容 SHA-256 校验值
 * @param contentSize   内容大小（字节）
 * @param status        状态
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @param createdBy     创建人
 * @param updatedBy     更新人
 */
public record SkillResource(
        Long id,
        String skillId,
        int versionNumber,
        String name,
        String description,
        SkillType skillType,
        SkillStorageType storageType,
        String storageKey,
        String contentSha256,
        long contentSize,
        SkillStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z][\\p{IsHan}a-zA-Z0-9_\\-]{0,127}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$", Pattern.CASE_INSENSITIVE);

    /**
     * 紧凑构造器：不变量校验
     */
    public SkillResource {
        if (StringUtils.isBlank(skillId)) {
            throw new IllegalArgumentException("技能ID不能为空");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("版本号必须大于0");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("技能名称长度不能超过255个字符");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("技能名称只能包含中文、英文字母、数字、下划线和连字符，且不能以数字或特殊字符开头");
        }
        if (StringUtils.isNotEmpty(description) && description.length() > 1000) {
            throw new IllegalArgumentException("描述不能超过1000个字符");
        }
        if (StringUtils.isBlank(contentSha256)) {
            throw new IllegalArgumentException("内容SHA256校验值不能为空");
        }
        if (!SHA256_PATTERN.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("内容SHA256校验值格式非法（应为64位十六进制）");
        }
        if (contentSize < 0) {
            throw new IllegalArgumentException("内容大小不能为负数");
        }
    }

    /**
     * 创建技能版本（默认状态 ACTIVE）
     */
    public static SkillResource create(
            String skillId,
            int versionNumber,
            String name,
            String description,
            SkillType skillType,
            SkillStorageType storageType,
            String storageKey,
            String contentSha256,
            long contentSize
    ) {
        return new SkillResource(
                null, skillId, versionNumber, name, description,
                skillType != null ? skillType : SkillType.CUSTOM,
                storageType != null ? storageType : SkillStorageType.LOCAL_FILE,
                storageKey, contentSha256, contentSize, SkillStatus.ACTIVE,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                null, null
        );
    }

    /**
     * 从数据库恢复（查询场景）
     */
    public static SkillResource restore(
            Long id,
            String skillId,
            int versionNumber,
            String name,
            String description,
            SkillType skillType,
            SkillStorageType storageType,
            String storageKey,
            String contentSha256,
            long contentSize,
            SkillStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String createdBy,
            String updatedBy
    ) {
        return new SkillResource(
                id, skillId, versionNumber, name, description,
                skillType, storageType, storageKey, contentSha256, contentSize,
                status, createdAt, updatedAt, createdBy, updatedBy
        );
    }
}