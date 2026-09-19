package com.linkroa.deepdataagent.shared.result;

import org.apache.commons.lang3.StringUtils;

/**
 * Cursor 游标分页统一请求参数（shared/api-conventions：limit 默认 20 / range 1-100，
 * after_id / before_id 定位游标）。
 * <p>controller 层解析小工具：HTTP 原始参数经 {@link #parse} 归一为类型化参数；
 * limit 缺省 20，越界（0 或 &gt;100 或非整数）抛 {@link IllegalArgumentException}
 * → 全局异常处理器收敛为 {@code 400 invalid_request_error}（对齐 spec「limit 越界」场景）；
 * after_id 与 before_id 互斥（同时传入拒绝），单游标向后/向前翻页。</p>
 *
 * @param limit    每页条数（1-100，缺省 20）
 * @param afterId  向后翻页游标（当页 last_id，可空）
 * @param beforeId 向前翻页游标（当页 first_id，可空）
 */
public record CursorPageParams(int limit, String afterId, String beforeId) {

    /** 缺省每页条数。 */
    public static final int DEFAULT_LIMIT = 20;

    /** 每页条数上限。 */
    public static final int MAX_LIMIT = 100;

    public CursorPageParams {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 必须在 1-" + MAX_LIMIT + " 之间");
        }
        if (StringUtils.isNotBlank(afterId) && StringUtils.isNotBlank(beforeId)) {
            throw new IllegalArgumentException("after_id 与 before_id 互斥，不能同时传入");
        }
        afterId = StringUtils.trimToNull(afterId);
        beforeId = StringUtils.trimToNull(beforeId);
    }

    /**
     * 解析 HTTP 原始查询参数（limit 非整数视为非法）。
     *
     * @param limit    limit 参数原文（可空 = 缺省 20）
     * @param afterId  after_id 参数原文（可空）
     * @param beforeId before_id 参数原文（可空）
     * @return 类型化游标分页参数
     */
    public static CursorPageParams parse(String limit, String afterId, String beforeId) {
        if (StringUtils.isBlank(limit)) {
            return new CursorPageParams(DEFAULT_LIMIT, afterId, beforeId);
        }
        try {
            return new CursorPageParams(Integer.parseInt(limit.trim()), afterId, beforeId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit 必须为整数: " + limit);
        }
    }

    /**
     * 解析 HTTP 原始查询参数（含不透明 {@code page} 游标）。
     * <p>{@code page} 为响应 {@code next_page} 原样回传的不透明游标（语义等价于
     * {@code after_id}），与 {@code after_id} / {@code before_id} 互斥——同时传入即
     * 抛 {@link IllegalArgumentException}（400 {@code invalid_request_error}）。</p>
     *
     * @param limit    limit 参数原文（可空 = 缺省 20）
     * @param page     不透明下页游标原文（可空）
     * @param afterId  after_id 参数原文（可空）
     * @param beforeId before_id 参数原文（可空）
     * @return 类型化游标分页参数（page 已归一为 after_id）
     */
    public static CursorPageParams parse(String limit, String page, String afterId, String beforeId) {
        String resolvedAfterId = afterId;
        if (StringUtils.isNotBlank(page)) {
            if (StringUtils.isNotBlank(afterId) || StringUtils.isNotBlank(beforeId)) {
                throw new IllegalArgumentException("page 与 after_id / before_id 互斥，不能同时传入");
            }
            resolvedAfterId = page;
        }
        return parse(limit, resolvedAfterId, beforeId);
    }
}
