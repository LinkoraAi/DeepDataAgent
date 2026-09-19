package com.linkroa.deepdataagent.agent.application.query;

import com.linkroa.deepdataagent.shared.result.CursorPageParams;

import java.time.OffsetDateTime;

/**
 * 运行环境游标列表查询（6.6 管理面 Cursor 约定，替代旧 page/size 分页）。
 *
 * @param ownerId       归属用户 ID（必填，owner 隔离）
 * @param metadataJson  metadata 包含过滤（JSON 对象文本，可空 = 不过滤）
 * @param createdAfter  创建时间下界（含，可空）
 * @param createdBefore 创建时间上界（含，可空）
 * @param cursor        游标分页参数（必填）
 */
public record ListEnvironmentQuery(
        Long ownerId,
        String metadataJson,
        OffsetDateTime createdAfter,
        OffsetDateTime createdBefore,
        CursorPageParams cursor
) {

    public ListEnvironmentQuery {
        if (ownerId == null) {
            throw new IllegalArgumentException("归属用户不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("游标分页参数不能为空");
        }
    }
}
