package com.linkroa.deepdataagent.shared.exception;

/**
 * 请求过于频繁（HTTP 429）——认证等公开接口的防爆破限流。
 */
public class TooManyRequestsException extends RuntimeException {

    /**
     * 构造 429 语义异常，消息随错误信封下发。
     *
     * @param message 限流原因
     */
    public TooManyRequestsException(String message) {
        super(message);
    }
}