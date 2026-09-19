package com.linkroa.deepdataagent.shared.storage;

/**
 * 对象存储技术异常（非受检）。
 * <p>由 {@link ObjectStorage} 的具体后端在协议 / IO 失败时抛出，携带
 * {@link ObjectStorageErrorKind} 错误分类；消息中 MUST NOT 包含 endpoint、
 * 访问密钥等敏感信息（可含桶名 / key 等定位信息，日志仍统一脱敏）。
 * 消费方按 {@link #kind()} 翻译为各自业务语义，未被翻译的异常由全局异常处理
 * 兜底为 500。</p>
 */
public class ObjectStorageException extends RuntimeException {

    /** 错误分类（非空）。 */
    private final ObjectStorageErrorKind kind;

    /**
     * @param kind    错误分类
     * @param message 技术错误描述（不得含凭证）
     */
    public ObjectStorageException(ObjectStorageErrorKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * @param kind    错误分类
     * @param message 技术错误描述（不得含凭证）
     * @param cause   底层协议 / IO 异常
     */
    public ObjectStorageException(ObjectStorageErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * 获取错误分类。
     *
     * @return 错误分类
     */
    public ObjectStorageErrorKind kind() {
        return kind;
    }
}
