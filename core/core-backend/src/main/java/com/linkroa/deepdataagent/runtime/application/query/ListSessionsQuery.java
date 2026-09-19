package com.linkroa.deepdataagent.runtime.application.query;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话列表游标查询（shared/api-conventions Cursor 约定 + 公开契约 GET 列表过滤集合）。
 * <p>过滤维度严格对齐公开契约：{@code agent_id / agent_version / deployment_id /
 * memory_store_id / statuses[]（对外四态子集）/ include_archived / created_at[gt|gte|lt|lte] /
 * order}；{@code environment_id}（自造字段）与 {@code metadata}（beta 搜索能力）均
 * <b>不是</b>受支持的过滤参数。归档为 {@code archived_at} 正交维度：缺省排除，
 * {@code includeArchived=true} 时纳入。</p>
 *
 * @param userId          用户 ID（owner 隔离）
 * @param agentId         Agent ID 过滤（可空 = 不过滤）
 * @param agentVersion    Agent 版本过滤（可空 = 不过滤）
 * @param deploymentId    调度来源过滤（可空 = 不过滤；对外别名 deployment_id）
 * @param memoryStoreId   记忆库过滤（可空 = 不过滤）
 * @param statuses        会话状态集合过滤（空 = 不过滤）
 * @param includeArchived true=纳入已归档会话（缺省排除）
 * @param createdAtGt     创建时间严格下界（可空 = 不过滤）
 * @param createdAtGte    创建时间下界（可空 = 不过滤）
 * @param createdAtLt     创建时间严格上界（可空 = 不过滤）
 * @param createdAtLte    创建时间上界（可空 = 不过滤）
 * @param ascending       是否创建时间升序（{@code order=asc}；缺省降序）
 * @param cursor          游标分页参数（limit 1-100 缺省 20 + page/after_id/before_id 互斥）
 */
public record ListSessionsQuery(
        String userId,
        String agentId,
        String agentVersion,
        String deploymentId,
        String memoryStoreId,
        List<AgentSessionStatus> statuses,
        boolean includeArchived,
        OffsetDateTime createdAtGt,
        OffsetDateTime createdAtGte,
        OffsetDateTime createdAtLt,
        OffsetDateTime createdAtLte,
        boolean ascending,
        CursorPageParams cursor
) {

    public ListSessionsQuery {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("游标分页参数不能为空");
        }
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        agentId = StringUtils.trimToNull(agentId);
        agentVersion = StringUtils.trimToNull(agentVersion);
        deploymentId = StringUtils.trimToNull(deploymentId);
        memoryStoreId = StringUtils.trimToNull(memoryStoreId);
    }
}