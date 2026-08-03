package com.linkroa.deepdataagent.shared.exception;

/**
 * 系统繁忙异常
 * <p>当会话事件总线或 Agent 执行池达到上限，无法接受新的分析请求时抛出，
 * 由全局异常处理器统一转换为错误响应。</p>
 */
public class SystemBusyException extends DeepDataAgentException {

    /**
     * 构造方法
     *
     * @param message 异常消息
     */
    public SystemBusyException(String message) {
        super(message);
    }
}