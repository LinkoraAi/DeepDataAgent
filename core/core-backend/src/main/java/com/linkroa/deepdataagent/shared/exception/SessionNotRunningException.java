package com.linkroa.deepdataagent.shared.exception;

/**
 * 会话未在运行中异常
 * <p>当客户端以 {@code resumeOnly} 方式请求续流，但对应会话已不在运行中时抛出，
 * 由控制器转换为 404 响应，表示无需恢复且不产生任何副作用（绝不启动新分析）。</p>
 */
public class SessionNotRunningException extends DeepDataAgentException {

    /**
     * 构造方法
     *
     * @param message 异常消息
     */
    public SessionNotRunningException(String message) {
        super(message);
    }
}