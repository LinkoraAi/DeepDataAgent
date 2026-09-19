package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Session 列表游标查询条件（公开契约 GET 列表过滤集合，不含 {@code environment_id}）。
 * <p>过滤集合严格对齐公开契约：{@code agent_id} / {@code agent_version} / {@code deployment_id} /
 * {@code memory_store_id} / {@code statuses[]}（对外四态子集）/ {@code include_archived}
 * （默认 false = 排除 {@code archived_at} 非空行）/ {@code created_at[gt|gte|lt|lte]} / {@code order}。
 * 归档已从状态值域移出——归档排除只看 {@code archived_at IS NULL}，与 statuses 无耦合。</p>
 * <p>排序为 {@code created_at + id} keyset（{@code id} 为稳定次键）；{@code ascending}
 * 由 {@code order} 参数派生（默认 {@code desc} = 创建时间降序）；keyset 游标位置
 * （{@code cursorCreatedAt} + {@code cursorRowId}）由应用层将 page/before_id/after_id 解析为
 * 行位点后装配，两者必须成对出现；{@code reverse} 为 {@code before_id} 方向（取更新侧，
 * 升序读取后由应用层翻转回请求方向）。</p>
 *
 * @param agentId         Agent 过滤（可空 = 不过滤）
 * @param agentVersion    Agent 版本过滤（可空 = 不过滤）
 * @param deploymentId    调度器触发来源过滤（可空 = 不过滤；落 {@code trigger_id} 列）
 * @param memoryStoreId   记忆库过滤（可空 = 不过滤；{@code memory_store_ids} JSONB 包含查询）
 * @param statuses        对外状态集合过滤（空 = 不过滤）
 * @param metadataJson    元数据键值包含过滤 JSON 文本（可空 = 不过滤；metadata JSONB {@code @>} 包含）
 * @param includeArchived true=包含已归档会话（默认 false = 仅返回 {@code archived_at IS NULL} 行）
 * @param createdAtGt     创建时间严格下界（可空 = 不过滤）
 * @param createdAtGte    创建时间下界（可空 = 不过滤）
 * @param createdAtLt     创建时间严格上界（可空 = 不过滤）
 * @param createdAtLte    创建时间上界（可空 = 不过滤）
 * @param ascending       true=创建时间升序（{@code order=asc}），false=降序（默认）
 * @param cursorCreatedAt 游标行创建时间（可空 = 首页）
 * @param cursorRowId     游标行数据库主键（次排序键定位；与游标时间成对）
 * @param reverse         true=before_id 方向（取更新侧、升序读取后由应用层翻转回请求方向）
 */
public record SessionListFilter(
        String agentId,
        String agentVersion,
        String deploymentId,
        String memoryStoreId,
        List<AgentSessionStatus> statuses,
        String metadataJson,
        boolean includeArchived,
        OffsetDateTime createdAtGt,
        OffsetDateTime createdAtGte,
        OffsetDateTime createdAtLt,
        OffsetDateTime createdAtLte,
        boolean ascending,
        OffsetDateTime cursorCreatedAt,
        Long cursorRowId,
        boolean reverse
) {

    public SessionListFilter {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        if ((cursorCreatedAt == null) != (cursorRowId == null)) {
            throw new IllegalArgumentException("游标位置必须成对提供（创建时间与行号）");
        }
    }
}