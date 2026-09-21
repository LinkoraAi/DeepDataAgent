package com.linkroa.deepdataagent.rag.application.task;

import org.apache.commons.lang3.StringUtils;

/**
 * 摄入管线阶段异常：为原始异常附加 {@link IngestionStage} 归类标记。
 * <p>{@code IngestionWorker} 各失败段捕获裸异常后以本异常重抛，
 * {@code errorMessage} 前缀由阶段标记统一推导；
 * 管线边界取消检查失败直接以 {@link IngestionStage#CANCELLED} 构造，
 * 与真实故障区分。</p>
 *
 * @author DeepDataAgent
 */
public class IngestionStageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败归位的管线阶段标记（非空）。 */
    private final IngestionStage stage;

    /**
     * 以阶段标记包装原始运行时异常（原始消息空白时回落异常类名）。
     *
     * @param stage 失败阶段标记
     * @param cause 原始异常
     */
    public IngestionStageException(IngestionStage stage, RuntimeException cause) {
        super(StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName()), cause);
        this.stage = stage;
    }

    /**
     * 以阶段标记与显式消息构造（无原始异常，如取消中断）。
     *
     * @param stage   失败阶段标记
     * @param message 异常消息
     */
    public IngestionStageException(IngestionStage stage, String message) {
        super(message);
        this.stage = stage;
    }

    /**
     * 失败归位的管线阶段标记。
     *
     * @return 阶段枚举（非空）
     */
    public IngestionStage stage() {
        return stage;
    }
}
