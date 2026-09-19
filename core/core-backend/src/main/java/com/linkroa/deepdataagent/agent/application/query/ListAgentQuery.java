package com.linkroa.deepdataagent.agent.application.query;

import com.linkroa.deepdataagent.shared.result.CursorPageParams;

import java.time.OffsetDateTime;

/**
 * Agent 列表游标查询（shared/api-conventions Cursor 约定，6.2 管理面）。
 * <p>列表按创建时间降序、不提供 total；过滤维度：名称模糊、状态（active/archived）、
 * metadata 键值包含（按激活版本快照匹配）、创建时间范围。</p>
 *
 * @param keyword      名称模糊匹配（可空）
 * @param status       状态过滤：active（默认）/ archived
 * @param metadataJson 元数据键值包含过滤 JSON 文本（可空 = 不过滤）
 * @param createdAtFrom 创建时间下界（含，可空）
 * @param createdAtTo   创建时间上界（含，可空）
 * @param cursor        游标分页参数（limit 1-100 缺省 20 + after_id/before_id 互斥）
 */
public record ListAgentQuery(
        String keyword,
        String status,
        String metadataJson,
        OffsetDateTime createdAtFrom,
        OffsetDateTime createdAtTo,
        CursorPageParams cursor
) {

    /** 未归档状态过滤值。 */
    public static final String STATUS_ACTIVE = "active";

    /** 已归档状态过滤值。 */
    public static final String STATUS_ARCHIVED = "archived";

    public ListAgentQuery {
        if (status != null && !STATUS_ACTIVE.equals(status) && !STATUS_ARCHIVED.equals(status)) {
            throw new IllegalArgumentException("status 仅支持 active / archived");
        }
    }
}
