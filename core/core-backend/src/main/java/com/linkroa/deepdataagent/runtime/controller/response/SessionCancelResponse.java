package com.linkroa.deepdataagent.runtime.controller.response;

/**
 * 取消会话响应（对齐 Managed Agents {@code POST /sessions/{id}/cancel}）。
 * <p>固定回执：{@code {id, type:"session", status:"canceling"}}——{@code canceling} 是响应固定值
 * 而非持久化状态（会话对外状态在取消收敛期间仍为 running，收束后回 idle）。
 * 存在活跃 turn 时返回 HTTP 202；idle / terminated（均无活跃 turn）为幂等空操作返回 HTTP 200，
 * 两种 2xx 的响应体完全相同。</p>
 *
 * @param id     会话 ID（sess_ 前缀）
 * @param type   对象类型（恒 {@code session}）
 * @param status 固定回执状态（恒 {@code canceling}）
 */
public record SessionCancelResponse(
        String id,
        String type,
        String status
) {

    /** 固定回执装配（type 恒 session、status 恒 canceling）。 */
    public SessionCancelResponse(String id) {
        this(id, "session", "canceling");
    }
}