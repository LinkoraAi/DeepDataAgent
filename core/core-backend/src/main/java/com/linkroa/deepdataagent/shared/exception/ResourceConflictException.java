package com.linkroa.deepdataagent.shared.exception;

/**
 * 资源冲突异常（对应 HTTP 409）。
 * <p>名称重复、仍被引用不可删除等冲突场景抛出。</p>
 */
public class ResourceConflictException extends RuntimeException {

    /**
     * 构造 409 语义异常，消息随错误信封下发。
     *
     * @param message 冲突原因
     */
    public ResourceConflictException(String message) {
        super(message);
    }

    /**
     * 构造携带原因链的 409 语义异常。
     *
     * @param message 冲突原因
     * @param cause 触发冲突的底层异常（保留以利诊断）
     */
    public ResourceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}