package com.linkroa.deepdataagent.skill.domain.model;

import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 技能壳对象（聚合根，对应 {@code skill} 表）。
 * <p>技能为文件包资产：壳对象承载展示名（创建后不可改）、来源与最新版本指针，
 * 内容按 {@code skill_id} + epoch 微秒版本键落在 {@link SkillVersion}（表
 * {@code skill_content_version}）+ 磁盘资产目录。展示名与来源创建后不可变更，
 * 唯一可变状态为 {@code latestVersion}（发版推进、版本删除后重算，全部版本删除后为 null）。
 * 删除为逻辑删除（历史版本数据保留）。</p>
 *
 * @param id            数据库主键
 * @param skillId       技能业务 ID（前缀 {@code skill_}）
 * @param displayTitle  展示名（≤255，创建后不可改）
 * @param source        技能来源（catalog / custom）
 * @param latestVersion 最新版本键（创建时刻 epoch 微秒字符串；全部版本删除后为 null）
 * @param metadata      业务自定义元数据（缺省空对象，归一为不可变 Map）
 * @param ownerId       归属用户 ID
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record SkillAsset(
        Long id,
        String skillId,
        String displayTitle,
        SkillSource source,
        String latestVersion,
        Map<String, Object> metadata,
        Long ownerId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /** 技能业务 ID 前缀。 */
    public static final String SKILL_ID_PREFIX = "skill_";
    /** 展示名长度上限（字符）。 */
    public static final int MAX_DISPLAY_TITLE_LENGTH = 255;

    /**
     * 紧凑构造器：领域不变量校验（ID 前缀、展示名边界、来源非空、归属非空、元数据归一不可变）。
     *
     * @throws IllegalArgumentException 任一不变量不满足
     */
    public SkillAsset {
        if (StringUtils.isBlank(skillId) || !skillId.startsWith(SKILL_ID_PREFIX)) {
            throw new IllegalArgumentException("技能ID非法，须以 skill_ 前缀");
        }
        if (StringUtils.isBlank(displayTitle)) {
            throw new IllegalArgumentException("技能展示名不能为空");
        }
        if (displayTitle.length() > MAX_DISPLAY_TITLE_LENGTH) {
            throw new IllegalArgumentException("技能展示名长度不能超过" + MAX_DISPLAY_TITLE_LENGTH + "个字符");
        }
        if (source == null) {
            throw new IllegalArgumentException("技能来源不能为空");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("技能归属用户不能为空");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 创建新的技能壳（尚无内容版本，{@code latestVersion} 为 null，随首个版本落库推进）。
     *
     * @param skillId      技能业务 ID（前缀 {@code skill_}）
     * @param displayTitle 展示名
     * @param source       技能来源
     * @param metadata     业务元数据（可空 = 空对象）
     * @param ownerId      归属用户 ID
     * @return 未落库的技能壳
     */
    public static SkillAsset create(String skillId, String displayTitle, SkillSource source,
                                    Map<String, Object> metadata, Long ownerId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new SkillAsset(null, skillId, displayTitle, source, null, metadata, ownerId, now, now);
    }

    /**
     * 从数据库恢复。
     */
    public static SkillAsset restore(Long id, String skillId, String displayTitle, SkillSource source,
                                     String latestVersion, Map<String, Object> metadata, Long ownerId,
                                     OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new SkillAsset(id, skillId, displayTitle, source, latestVersion, metadata, ownerId,
                createdAt, updatedAt);
    }

    /**
     * 派生「最新版本指针更新」后的壳（发版推进 / 版本删除后重算；{@code null} 表示已无版本）。
     *
     * @param latestVersion 新最新版本键（可空）
     * @return 版本指针更新后的壳快照
     */
    public SkillAsset withLatestVersion(String latestVersion) {
        return new SkillAsset(id, skillId, displayTitle, source, latestVersion, metadata, ownerId,
                createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
}