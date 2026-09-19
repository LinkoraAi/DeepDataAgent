package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话事件列表查询条件（事件游标分页与过滤的领域语义载体）。
 * <p>承载公开契约 {@code GET /sessions/{id}/events}（及线程作用域嵌套端点）的过滤集合：
 * 会话 / 线程归属、{@code after_id} / {@code before_id} 换算后的 seq 游标、{@code order}
 * 方向、{@code types} 过滤与 {@code created_at[gt|gte|lt|lte]} 时间区间。</p>
 * <p>{@code afterSeq} 与 {@code beforeSeq} 互斥（不可同时定位）；类型过滤为空表示不过滤。
 * 未知类型由调用方在协议层静默剔除后再传入（列表路径不因未知类型报错）。</p>
 *
 * @param sessionId       会话 ID（必填）
 * @param sessionThreadId 线程归属过滤（可空 = 不限线程）
 * @param afterSeq        向后游标（seq 大于该值，可空）
 * @param beforeSeq       向前游标（seq 小于该值，可空）
 * @param types           事件类型过滤（空 = 不过滤）
 * @param createdAtGt     created_at 严格大于（可空）
 * @param createdAtGte    created_at 大于等于（可空）
 * @param createdAtLt     created_at 严格小于（可空）
 * @param createdAtLte    created_at 小于等于（可空）
 * @param ascending       是否按 seq 升序读取（false = 降序）
 * @param limit           单页上限（≥1，调用方已含探针余量）
 */
public record ChatEventQuery(
        String sessionId,
        String sessionThreadId,
        Long afterSeq,
        Long beforeSeq,
        List<String> types,
        OffsetDateTime createdAtGt,
        OffsetDateTime createdAtGte,
        OffsetDateTime createdAtLt,
        OffsetDateTime createdAtLte,
        boolean ascending,
        int limit
) {

    public ChatEventQuery {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (afterSeq != null && beforeSeq != null) {
            throw new IllegalArgumentException("after 与 before 游标互斥，不能同时定位");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("单页上限必须为正整数: " + limit);
        }
        types = types == null ? List.of() : types.stream()
                .filter(StringUtils::isNotBlank)
                .toList();
        sessionThreadId = StringUtils.trimToNull(sessionThreadId);
    }
}