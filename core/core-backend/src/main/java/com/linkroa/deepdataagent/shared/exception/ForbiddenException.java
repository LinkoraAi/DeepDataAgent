package com.linkroa.deepdataagent.shared.exception;

/**
 * 禁止访问异常（对应 HTTP 403）。
 * <p>资源存在但当前操作被策略禁止的场景抛出（如文件 downloadable=false 时请求
 * {@code /content} 直接下载端点），由全局异常处理器映射为 403。</p>
 */
public class ForbiddenException extends RuntimeException {

    /**
     * 构造 403 语义异常，消息随错误信封下发。
     *
     * @param message 拒绝原因
     */
    public ForbiddenException(String message) {
        super(message);
    }
}
