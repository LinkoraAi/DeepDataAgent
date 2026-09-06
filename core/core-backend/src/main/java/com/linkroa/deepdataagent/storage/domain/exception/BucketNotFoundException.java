package com.linkroa.deepdataagent.storage.domain.exception;

/**
 * 桶不存在异常（对应 HTTP 404）。
 * <p>对象操作目标桶不存在、或删除不存在的桶时抛出，
 * 引导调用方先通过创建桶接口（createBucket）建立目标桶。</p>
 */
public class BucketNotFoundException extends RuntimeException {

    public BucketNotFoundException(String message) {
        super(message);
    }

    public BucketNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}