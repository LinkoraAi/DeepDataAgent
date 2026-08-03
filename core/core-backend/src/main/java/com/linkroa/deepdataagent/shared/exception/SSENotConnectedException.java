package com.linkroa.deepdataagent.shared.exception;

/**
 * SSE 未连接异常
 * <p>当客户端未建立 SSE 连接就发起数据分析请求时抛出，由全局异常处理器统一转换为错误响应。</p>
 */
public class SSENotConnectedException extends DeepDataAgentException {

    /**
     * 构造方法
     *
     * @param message 异常消息
     */
    public SSENotConnectedException(String message) {
        super(message);
    }
}