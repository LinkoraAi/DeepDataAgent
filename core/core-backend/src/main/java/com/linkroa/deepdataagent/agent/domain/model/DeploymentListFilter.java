package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;

import java.time.OffsetDateTime;

/**
 * 调度器列表游标查询条件（shared/api-conventions Cursor 约定，6.5 管理面）。
 * <p>排序为 {@code created_at DESC, id DESC}（创建时间降序、数据库行号稳定次键）；
 * keyset 游标位置（{@code cursorCreatedAt} + {@code cursorRowId}）由应用层将
 * after_id/before_id 解析为行位点后装配，两者必须成对出现。</p>
 *
 * @param status            状态过滤（可空 = 不过滤，active/paused）
 * @param agentId           指向 Agent 过滤（可空 = 不过滤）
 * @param createdAfter      创建时间下界（含，可空 = 不过滤）
 * @param createdBefore     创建时间上界（含，可空 = 不过滤）
 * @param includeArchived   是否包含已归档（false=列表排除归档；true=一并返回）
 * @param cursorCreatedAt   游标行创建时间（可空 = 首页）
 * @param cursorRowId       游标行数据库主键（次排序键定位；与游标时间成对）
 * @param reverse           true=before_id 方向（取更新侧、升序读取后由应用层翻转回降序）
 */
public record DeploymentListFilter(
        DeploymentStatus status,
        String agentId,
        OffsetDateTime createdAfter,
        OffsetDateTime createdBefore,
        boolean includeArchived,
        OffsetDateTime cursorCreatedAt,
        Long cursorRowId,
        boolean reverse
) {

    public DeploymentListFilter {
        if ((cursorCreatedAt == null) != (cursorRowId == null)) {
            throw new IllegalArgumentException("游标位置必须成对提供（创建时间与行号）");
        }
    }
}
