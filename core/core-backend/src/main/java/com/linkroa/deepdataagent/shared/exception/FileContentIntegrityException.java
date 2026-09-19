package com.linkroa.deepdataagent.shared.exception;

/**
 * 文件内容一致性异常（对应 HTTP 500）。
 * <p>文件元数据记录存在，但磁盘内容缺失或 SHA-256 校验不一致（数据一致性事故）时抛出；
 * 由全局异常兜底处理器映射为 500，对外仅返回通用错误消息，事故细节以 ERROR 日志留痕，
 * 不向客户端返回空 / 损坏内容。</p>
 */
public class FileContentIntegrityException extends RuntimeException {

    /**
     * 构造 500 语义异常；消息仅进 ERROR 日志，对外收敛为通用错误消息。
     *
     * @param message 一致性事故描述（含文件标识与校验差异）
     */
    public FileContentIntegrityException(String message) {
        super(message);
    }
}
