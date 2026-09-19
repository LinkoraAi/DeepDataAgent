package com.linkroa.deepdataagent.shared.exception;

/**
 * 资源不存在异常（对应 HTTP 404）。
 * <p>资源（Agent / 模型配置 / 技能 / 版本）不存在或已删除时抛出。</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * 构造 404 语义异常，消息随错误信封下发。
     *
     * @param message 缺失说明（含资源标识）
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}