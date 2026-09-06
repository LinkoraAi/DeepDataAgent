package com.linkroa.deepdataagent.storage.domain.exception;

/**
 * 对象键冲突异常（对应 HTTP 409）。
 * <p>上传时 objectKey 已存在且未显式声明覆盖（或并发条件写入失败）时抛出，
 * 防止同名对象被静默覆盖造成数据丢失。</p>
 */
public class FileKeyConflictException extends RuntimeException {

    public FileKeyConflictException(String message) {
        super(message);
    }

    public FileKeyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}