package com.linkroa.deepdataagent.runtime.application.query;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 会话事件回放查询（事件溯源模型下按会话级 {@code seq} 游标分页，对齐 Managed Agents 回放契约）。
 *
 * @param sessionId        会话 ID
 * @param afterSequenceNum 回放起点（序列号大于该值的事件，0 表示全量回放）
 * @param types            事件类型过滤（字符串集合，如 {@code user.message} / {@code session.status_idle}；
 *                         null / 空表示不过滤；非法类型由控制器提前校验并返回 400 {@code unknown_event_type}）
 * @param limit            单页上限（null 表示不限量，SSE 全量回放用；正整数方可）
 */
public record ReplayQuery(String sessionId, long afterSequenceNum, List<String> types, Integer limit) {

    public ReplayQuery {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (afterSequenceNum < 0) {
            throw new IllegalArgumentException("回放起点序列号不能为负数");
        }
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("回放单页上限必须为正整数");
        }
        types = types == null ? List.of() : types;
    }

    /**
     * 便捷构造（不含类型过滤、不限量）。
     *
     * @param sessionId        会话 ID
     * @param afterSequenceNum 回放起点（0 表示全量回放）
     */
    public static ReplayQuery of(String sessionId, long afterSequenceNum) {
        return new ReplayQuery(sessionId, afterSequenceNum, List.of(), null);
    }

    /**
     * 便捷构造（含类型过滤、不限量，兼容事件流全量回放调用）。
     *
     * @param sessionId        会话 ID
     * @param afterSequenceNum 回放起点（0 表示全量回放）
     * @param types            事件类型过滤（null / 空表示不过滤）
     */
    public ReplayQuery(String sessionId, long afterSequenceNum, List<String> types) {
        this(sessionId, afterSequenceNum, types, null);
    }
}