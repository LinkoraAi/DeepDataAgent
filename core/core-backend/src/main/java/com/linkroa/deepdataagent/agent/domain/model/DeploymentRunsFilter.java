package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;

/**
 * 调度运行记录游标查询条件（6.5 管理面，单调度器与全局两种作用域共用）。
 * <p>排序为 {@code created_at DESC, id DESC}（触发时间降序、数据库行号稳定次键）；
 * {@code deploymentId} 非空为单调度器作用域，{@code ownerId} 非空为全局作用域
 * （经 deployment 表子查询限定归属），两者至少其一；keyset 游标位置
 * （{@code cursorCreatedAt} + {@code cursorRowId}）由应用层解析游标行位点后装配。</p>
 *
 * @param deploymentId      所属调度器业务ID（可空 = 全局作用域）
 * @param ownerId           归属用户 ID（可空 = 不限定；全局列表按归属子查询过滤）
 * @param createdAfter      触发时间下界（含，可空 = 不过滤）
 * @param createdBefore     触发时间上界（含，可空 = 不过滤）
 * @param cursorCreatedAt   游标行创建时间（可空 = 首页）
 * @param cursorRowId       游标行数据库主键（次排序键定位；与游标时间成对）
 * @param reverse           true=before_id 方向（升序读取后由应用层翻转回降序）
 */
public record DeploymentRunsFilter(
        String deploymentId,
        Long ownerId,
        OffsetDateTime createdAfter,
        OffsetDateTime createdBefore,
        OffsetDateTime cursorCreatedAt,
        Long cursorRowId,
        boolean reverse
) {

    public DeploymentRunsFilter {
        if (StringUtils.isBlank(deploymentId) && ownerId == null) {
            throw new IllegalArgumentException("运行记录查询必须限定调度器或归属用户");
        }
        if ((cursorCreatedAt == null) != (cursorRowId == null)) {
            throw new IllegalArgumentException("游标位置必须成对提供（创建时间与行号）");
        }
    }
}
