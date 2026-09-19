package com.linkroa.deepdataagent.agent.application.query;

import com.linkroa.deepdataagent.shared.result.CursorPageParams;

import java.time.OffsetDateTime;

/**
 * 调度运行记录游标列表查询（6.5 管理面，单调度器与全局两种作用域共用）。
 *
 * @param deploymentId   调度器业务ID（非空 = 单调度器作用域，应用层已校验归属；可空 = 全局）
 * @param ownerId        归属用户 ID（全局作用域经 deployment 归属过滤；必填）
 * @param createdAfter   触发时间下界（含，可空）
 * @param createdBefore  触发时间上界（含，可空）
 * @param cursor         游标分页参数（必填）
 */
public record ListDeploymentRunsQuery(
        String deploymentId,
        Long ownerId,
        OffsetDateTime createdAfter,
        OffsetDateTime createdBefore,
        CursorPageParams cursor
) {

    public ListDeploymentRunsQuery {
        if (ownerId == null) {
            throw new IllegalArgumentException("归属用户不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("游标分页参数不能为空");
        }
    }
}
