package com.linkroa.deepdataagent.rag.domain.port;

import org.apache.commons.lang3.ObjectUtils;

/**
 * 模型调用失败异常：携带 {@link LlmFailureKind} 分类，供上层按失败性质分流。
 * <p>消息保持既有「{@code 模型接口调用失败: ...}」前缀，既有按前缀判断的调用方无需改动；
 * 新增的 {@link #kind()} 只用于日志与失败原因统计，不改变异常传播语义。</p>
 *
 * @author DeepDataAgent
 */
public class LlmCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败分类（非空，缺失回落 {@link LlmFailureKind#UNKNOWN}） */
    private final LlmFailureKind kind;

    /**
     * 构造带分类的调用失败异常。
     *
     * @param kind    失败分类（可空，回落 UNKNOWN）
     * @param message 异常消息
     */
    public LlmCallException(LlmFailureKind kind, String message) {
        super(message);
        this.kind = ObjectUtils.isEmpty(kind) ? LlmFailureKind.UNKNOWN : kind;
    }

    /**
     * 构造带分类与原始异常的调用失败异常。
     *
     * @param kind    失败分类（可空，回落 UNKNOWN）
     * @param message 异常消息
     * @param cause   原始异常
     */
    public LlmCallException(LlmFailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = ObjectUtils.isEmpty(kind) ? LlmFailureKind.UNKNOWN : kind;
    }

    /**
     * 失败分类。
     *
     * @return 失败分类（非空）
     */
    public LlmFailureKind kind() {
        return kind;
    }
}
