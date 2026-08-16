package com.linkroa.deepdataagent.shared.exception;

/**
 * 资源冲突异常（对应 HTTP 409）。
 * <p>名称重复、仍被引用不可删除等冲突场景抛出。</p>
 */
public class ResourceConflictException extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }

    public ResourceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}