package com.linkroa.deepdataagent.shared.exception;

/**
 * 会话忙异常（对应 HTTP 409，错误信封 {@code type=invalid_request_error}）。
 *
 * <p>公开契约对「会话存在活跃 turn 时拒绝新消息」明文规定 409 的错误 type 为
 * {@code invalid_request_error}（非常规资源的 {@code conflict_error}），故本异常为
 * 细粒度特例：仅用于契约明文路径——活跃 turn 拒绝新 user.message、
 * 创建期资源未就绪（file status 非 ready）等。其他资源的通用 409 语义
 * 仍由 {@link ResourceConflictException}（{@code conflict_error}）承载，口径不变。</p>
 */
public class SessionBusyException extends RuntimeException {

    /**
     * 构造 409 语义异常（错误 type 为 {@code invalid_request_error}），消息随错误信封下发。
     *
     * @param message 忙碌原因
     */
    public SessionBusyException(String message) {
        super(message);
    }

    /**
     * 构造携带原因链的会话忙异常。
     *
     * @param message 忙碌原因
     * @param cause 底层异常（保留以利诊断）
     */
    public SessionBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}