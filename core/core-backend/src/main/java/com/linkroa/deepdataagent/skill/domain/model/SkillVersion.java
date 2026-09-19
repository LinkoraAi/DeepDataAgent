package com.linkroa.deepdataagent.skill.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 技能内容版本值对象（{@code skill_content_version} 表映射，不可变快照的元数据部分）。
 * <p>版本键为创建时刻 epoch 微秒字符串（服务端时钟，同 skill 内唯一索引 + 写入自旋裁决），
 * 字典序与时间序一致。版本内容为不可变快照：包文件树落磁盘资产目录
 * {@code <root>/<skill_id>/<epoch>/}，本值对象仅承载 frontmatter 派生元数据与内部完整性校验值。</p>
 *
 * @param id           版本业务 ID（前缀 {@code skillver_}）
 * @param skillId      所属技能业务 ID（前缀 {@code skill_}）
 * @param version      版本键（创建时刻 epoch 微秒字符串，不可变）
 * @param name         frontmatter name（{@code ^[a-z0-9][a-z0-9_-]*$}，≤64，跨版本一致）
 * @param description  frontmatter description（非空，≤5120）
 * @param directory    包顶级目录名（恒等于 {@code name}）
 * @param contentSha256 包内容 SHA-256 校验值（hex，64 字符，内部完整性手段，不进入响应）
 * @param contentSize  包内容字节长度（解压后总量，≥0）
 * @param resources    资源文件相对路径 → 内容字节长度（可空，归一为不可变 Map）
 * @param createdAt    版本创建时间（落库行 created_at 回读）
 */
public record SkillVersion(
        String id,
        String skillId,
        String version,
        String name,
        String description,
        String directory,
        String contentSha256,
        long contentSize,
        Map<String, Long> resources,
        OffsetDateTime createdAt
) {

    /** 版本业务 ID 前缀。 */
    public static final String VERSION_ID_PREFIX = "skillver_";
    /** frontmatter name 长度上限（字符）。 */
    public static final int MAX_NAME_LENGTH = 64;
    /** frontmatter name 字符集（小写字母数字起头，其余可为数字 / 下划线 / 连字符）。 */
    public static final String NAME_PATTERN = "^[a-z0-9][a-z0-9_-]*$";
    /** frontmatter description 长度上限（字符）。 */
    public static final int MAX_DESCRIPTION_LENGTH = 5120;
    /** SHA-256 hex 摘要长度。 */
    private static final int SHA256_HEX_LENGTH = 64;

    /**
     * 紧凑构造器：不变量校验（ID 前缀、版本键、名称正则与边界、目录名一致、校验值格式、
     * 大小非负、资源清单归一不可变）。
     *
     * @throws IllegalArgumentException 任一不变量不满足
     */
    public SkillVersion {
        if (StringUtils.isBlank(id) || !id.startsWith(VERSION_ID_PREFIX)) {
            throw new IllegalArgumentException("技能版本ID非法，须以 skillver_ 前缀");
        }
        if (StringUtils.isBlank(skillId) || !skillId.startsWith(SkillAsset.SKILL_ID_PREFIX)) {
            throw new IllegalArgumentException("技能ID非法，须以 skill_ 前缀");
        }
        if (StringUtils.isBlank(version) || !version.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("技能版本键必须为非空十进制 epoch 微秒字符串");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("技能名称长度不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        if (!name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("技能名称须匹配 " + NAME_PATTERN + ": " + name);
        }
        if (StringUtils.isBlank(description)) {
            throw new IllegalArgumentException("技能描述不能为空");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("技能描述不能超过" + MAX_DESCRIPTION_LENGTH + "个字符");
        }
        if (!name.equals(directory)) {
            throw new IllegalArgumentException("技能包顶级目录名必须等于 frontmatter name");
        }
        if (StringUtils.isBlank(contentSha256) || contentSha256.length() != SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException("技能内容校验值必须为64位SHA-256摘要");
        }
        if (contentSize < 0) {
            throw new IllegalArgumentException("技能内容大小不能为负");
        }
        resources = resources == null ? Map.of() : Map.copyOf(resources);
    }

    /**
     * 创建首个内容版本（目录名取 frontmatter name，创建时间取当前时刻）。
     *
     * @param id            版本业务 ID（前缀 {@code skillver_}）
     * @param skillId       所属技能业务 ID
     * @param version       版本键（epoch 微秒字符串）
     * @param name          frontmatter name
     * @param description   frontmatter description
     * @param contentSha256 包内容 SHA-256 校验值
     * @param contentSize   包内容字节长度
     * @param resources     资源文件相对路径 → 字节长度
     * @return 未落库的内容版本
     */
    public static SkillVersion create(String id, String skillId, String version, String name, String description,
                                      String contentSha256, long contentSize, Map<String, Long> resources) {
        return new SkillVersion(id, skillId, version, name, description, name, contentSha256, contentSize,
                resources, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 从数据库恢复。
     */
    public static SkillVersion restore(String id, String skillId, String version, String name, String description,
                                       String directory, String contentSha256, long contentSize,
                                       Map<String, Long> resources, OffsetDateTime createdAt) {
        return new SkillVersion(id, skillId, version, name, description, directory, contentSha256, contentSize,
                resources, createdAt);
    }
}