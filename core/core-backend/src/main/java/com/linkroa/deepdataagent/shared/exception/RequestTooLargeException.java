package com.linkroa.deepdataagent.shared.exception;

/**
 * 请求载荷超限异常（对应 HTTP 413，错误类别 {@code request_too_large_error}）。
 * <p>用于请求体 / multipart 包体超过服务端限额的场景（如技能包 zip 超过 50MB 上限），
 * 由 {@code GlobalExceptionHandler} 收敛为 413 统一错误信封。容器级 multipart 限额
 * （{@code spring.servlet.multipart.max-file-size}）触发的是
 * {@code MaxUploadSizeExceededException}（400），二者分别在限额前后生效。</p>
 */
public class RequestTooLargeException extends RuntimeException {

    /**
     * 构造 413 语义异常，消息随错误信封下发。
     *
     * @param message 超限说明（如包体大小与限额）
     */
    public RequestTooLargeException(String message) {
        super(message);
    }
}