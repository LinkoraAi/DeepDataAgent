package com.linkroa.deepdataagent.shared.exception;

/**
 * 业务异常基类：承载跨限界上下文的通用业务失败语义。
 */
public class DeepDataAgentException extends RuntimeException {

    /**
     * 构造业务异常，消息随错误信封下发。
     *
     * @param message 业务失败原因
     */
    public DeepDataAgentException(String message) {
        super(message);
    }
}
