package com.linkroa.deepdataagent.storage.domain.exception;

/**
 * 桶操作冲突异常（对应 HTTP 409）。
 * <p>创建已存在的桶（重名）或删除非空桶时抛出。
 * 冲突必须对调用方可见：重名提示所有权冲突防止误写他人桶，非空防止误删数据。</p>
 */
public class BucketConflictException extends RuntimeException {

    public BucketConflictException(String message) {
        super(message);
    }

    public BucketConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}