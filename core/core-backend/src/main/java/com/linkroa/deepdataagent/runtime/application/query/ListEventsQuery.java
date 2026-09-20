package com.linkroa.deepdataagent.runtime.application.query;

import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 事件列表游标查询（公开契约 {@code GET /sessions/{id}/events} 与线程作用域嵌套端点的应用层载体）。
 * <p>过滤集合：{@code types}（已由协议层静默剔除未知类型后的可识别集合）/ {@code order}
 * （{@code ascending} 派生）/ {@code created_at[gt|gte|lt|lte]}；游标统一
 * {@link CursorPageParams}（{@code limit 1-100 缺省 20} + {@code after_id / before_id} 的
 * {@code evt_} 事件 ID 定位，两者互斥）。</p>
 * <p>{@code sessionThreadId} 为事件表内部过滤键（线程作用域端点装配；会话级列表为 null），
 * MUST NOT 因此扩张对外扁平 Event 的公开字段。</p>
 *
 * @param sessionId       会话 ID（必填）
 * @param sessionThreadId 线程归属过滤（可空 = 不限线程）
 * @param types           事件类型过滤（可识别类型集合，空 = 不过滤）
 * @param createdAtGt     created_at 严格大于（可空 = 不过滤）
 * @param createdAtGte    created_at 大于等于（可空 = 不过滤）
 * @param createdAtLt     created_at 严格小于（可空 = 不过滤）
 * @param createdAtLte    created_at 小于等于（可空 = 不过滤）
 * @param ascending       true = 按 seq 升序（{@code order=asc}，缺省），false = 降序
 * @param cursor          游标分页参数（limit + after_id / before_id 互斥）
 */
public record ListEventsQuery(
        String sessionId,
        String sessionThreadId,
        List<ChatEventType> types,
        OffsetDateTime createdAtGt,
        OffsetDateTime createdAtGte,
        OffsetDateTime createdAtLt,
        OffsetDateTime createdAtLte,
        boolean ascending,
        CursorPageParams cursor
) {

    public ListEventsQuery {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("游标分页参数不能为空");
        }
        types = types == null ? List.of() : List.copyOf(types);
        sessionThreadId = StringUtils.trimToNull(sessionThreadId);
    }
}