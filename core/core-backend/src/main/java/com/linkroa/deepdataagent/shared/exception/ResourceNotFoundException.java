package com.linkroa.deepdataagent.shared.exception;

/**
 * 资源不存在异常（对应 HTTP 404）。
 * <p>资源（Agent / 模型配置 / 技能 / 版本）不存在或已删除时抛出。</p>
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}