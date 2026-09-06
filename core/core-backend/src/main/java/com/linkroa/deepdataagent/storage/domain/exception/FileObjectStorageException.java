package com.linkroa.deepdataagent.storage.domain.exception;

/**
 * 对象存储异常（对应 HTTP 500）。
 * <p>S3 协议层异常（非 404 不存在 / 非 412 条件写入冲突）统一翻译为本异常，
 * 表示底层存储不可用或操作失败，避免落入非预期的业务语义。</p>
 */
public class FileObjectStorageException extends RuntimeException {

    public FileObjectStorageException(String message) {
        super(message);
    }

    public FileObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}