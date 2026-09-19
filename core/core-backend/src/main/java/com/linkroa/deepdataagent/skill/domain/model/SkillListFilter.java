package com.linkroa.deepdataagent.skill.domain.model;

import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;

import java.time.OffsetDateTime;

/**
 * 技能列表游标查询条件（shared/api-conventions Cursor 约定）。
 * <p>排序为 {@code created_at DESC, id DESC}（创建时间降序、数据库行号稳定次键）；
 * keyset 游标位置（{@code cursorCreatedAt} + {@code cursorRowId}）由应用层将
 * after_id/before_id 解析为行位点后装配，两者必须成对出现。</p>
 *
 * @param source          来源过滤（可空 = 不过滤，catalog/custom）
 * @param keyword         展示名模糊过滤（可空 = 不过滤）
 * @param cursorCreatedAt 游标行创建时间（可空 = 首页）
 * @param cursorRowId     游标行数据库主键（次排序键定位；与游标时间成对）
 * @param reverse         true=before_id 方向（取更新侧、升序读取后由应用层翻转回降序）
 */
public record SkillListFilter(
        SkillSource source,
        String keyword,
        OffsetDateTime cursorCreatedAt,
        Long cursorRowId,
        boolean reverse
) {

    public SkillListFilter {
        if ((cursorCreatedAt == null) != (cursorRowId == null)) {
            throw new IllegalArgumentException("游标位置必须成对提供（创建时间与行号）");
        }
    }
}