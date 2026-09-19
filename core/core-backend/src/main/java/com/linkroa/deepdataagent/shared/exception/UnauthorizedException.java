package com.linkroa.deepdataagent.shared.exception;

/**
 * 未认证异常（对应 HTTP 401）。
 * <p>登录失败、缺失 / 非法 / 过期 JWT 等认证失败场景抛出。</p>
 */
public class UnauthorizedException extends RuntimeException {

    /**
     * 构造 401 语义异常，消息随错误信封下发。
     *
     * @param message 认证失败原因
     */
    public UnauthorizedException(String message) {
        super(message);
    }
}