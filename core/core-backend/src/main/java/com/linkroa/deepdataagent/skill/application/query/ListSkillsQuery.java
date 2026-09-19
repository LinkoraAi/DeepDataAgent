package com.linkroa.deepdataagent.skill.application.query;

import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import org.apache.commons.lang3.StringUtils;

/**
 * 技能列表游标查询（shared/api-conventions Cursor 约定）。
 * <p>列表按创建时间降序、不提供 total；过滤维度：技能来源（catalog/custom）与展示名模糊关键字。</p>
 *
 * @param ownerId 归属用户 ID（owner 隔离）
 * @param source  来源过滤（可空 = 不过滤）
 * @param keyword 展示名模糊过滤（空白归一为 null = 不过滤）
 * @param cursor  游标分页参数（limit 1-100 缺省 20 + after_id/before_id 互斥）
 */
public record ListSkillsQuery(
        Long ownerId,
        SkillSource source,
        String keyword,
        CursorPageParams cursor
) {

    public ListSkillsQuery {
        if (ownerId == null) {
            throw new IllegalArgumentException("归属用户不能为空");
        }
        keyword = StringUtils.isBlank(keyword) ? null : keyword.trim();
    }
}