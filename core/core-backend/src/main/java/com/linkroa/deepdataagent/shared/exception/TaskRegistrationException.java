package com.linkroa.deepdataagent.shared.exception;

/**
 * 在飞任务登记异常。
 * <p>受理事务内登记在飞任务失败时抛出，用于触发受理事务整体回滚；底层异常（如缓存连接、
 * 超时）仅作为 cause 保留给服务端日志，对外统一由全局异常处理器映射为 500 通用文案，
 * MUST NOT 向客户端暴露底层实现细节。</p>
 *
 * @author DeepDataAgent
 */
public class TaskRegistrationException extends RuntimeException {

    /**
     * 构造在飞任务登记异常。
     *
     * @param message 通用失败文案（随错误信封下发）
     * @param cause   底层异常（仅记录日志）
     */
    public TaskRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}