package com.linkroa.deepdataagent.agent.application.query;

import com.linkroa.deepdataagent.agent.domain.model.enums.DeploymentStatus;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;

import java.time.OffsetDateTime;

/**
 * 调度器游标列表查询（6.5 管理面 Cursor 约定）。
 *
 * @param ownerId          归属用户 ID（必填，owner 隔离）
 * @param status           状态过滤（可空 = 不过滤）
 * @param agentId          指向 Agent 过滤（可空 = 不过滤）
 * @param createdAfter     创建时间下界（含，可空）
 * @param createdBefore    创建时间上界（含，可空）
 * @param includeArchived  是否包含已归档（默认 false）
 * @param cursor           游标分页参数（必填）
 */
public record ListDeploymentsQuery(
        Long ownerId,
        DeploymentStatus status,
        String agentId,
        OffsetDateTime createdAfter,
        OffsetDateTime createdBefore,
        boolean includeArchived,
        CursorPageParams cursor
) {

    public ListDeploymentsQuery {
        if (ownerId == null) {
            throw new IllegalArgumentException("归属用户不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("游标分页参数不能为空");
        }
    }
}
