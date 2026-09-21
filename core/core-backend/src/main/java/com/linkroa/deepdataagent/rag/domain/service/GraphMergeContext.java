package com.linkroa.deepdataagent.rag.domain.service;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 图合并执行上下文值对象（单次文档级图合并的全部输入与运行参数）。
 * <p>承载合并流程所需的最小上下文：库/文档身份（溯源键）、
 * 摘要与向量化各自的模型引用（远程调用严禁进入事务，见）、
 * Prompt 语言、可调限额 {@link GraphMergeParams}、审计操作人，
 * 以及可协作取消检查（用户取消解析时中断合并，见）。</p>
 *
 * @param kbId                   所属知识库ID（必填）
 * @param documentId             来源文档ID（账本溯源与审计定位键，必填）
 * @param summaryModelProfileId  摘要 LLM 模型 profileId（CHAT 类型，必填）
 * @param embeddingModelProfileId 向量化模型 profileId（EMBEDDING 类型，必填）
 * @param language               知识库语言全名（兼容历史裸语言码；空白时按英文处理，
 * @param params                 图合并参数（null 回退默认值）
 * @param operator               操作人标识（审计字段，可空）
 * @param cancellationCheck      取消检查器（null 视为永不取消）
 * @param llmCacheHits           LLM 缓存命中计数器（可空；非空时摘要每次缓存回放
 *                               递增一次，供摄入收尾结构化日志汇总）
 * @author DeepDataAgent
 */
public record GraphMergeContext(
        Long kbId,
        Long documentId,
        String summaryModelProfileId,
        String embeddingModelProfileId,
        String language,
        GraphMergeParams params,
        String operator,
        BooleanSupplier cancellationCheck,
        AtomicInteger llmCacheHits) {

    /**
     * 紧凑构造器：不变量校验与兜底。
     */
    public GraphMergeContext {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("图合并上下文 kbId 不能为空");
        }
        if (ObjectUtils.isEmpty(documentId)) {
            throw new IllegalArgumentException("图合并上下文 documentId 不能为空");
        }
        if (StringUtils.isBlank(summaryModelProfileId)) {
            throw new IllegalArgumentException("图合并上下文 summaryModelProfileId 不能为空");
        }
        if (StringUtils.isBlank(embeddingModelProfileId)) {
            throw new IllegalArgumentException("图合并上下文 embeddingModelProfileId 不能为空");
        }
        language = StringUtils.isBlank(language) ? "en" : language;
        params = ObjectUtils.isEmpty(params) ? GraphMergeParams.defaults() : params;
    }

    /**
     * 便捷构造器：不挂缓存命中计数器（合并不统计回放次数）。
     *
     * @param kbId                    所属知识库ID
     * @param documentId              来源文档ID
     * @param summaryModelProfileId   摘要模型 profileId
     * @param embeddingModelProfileId 向量化模型 profileId
     * @param language                知识库语言全名（兼容历史裸语言码）
     * @param params                  图合并参数
     * @param operator                操作人标识
     * @param cancellationCheck       取消检查器
     */
    public GraphMergeContext(
            Long kbId, Long documentId, String summaryModelProfileId,
            String embeddingModelProfileId, String language, GraphMergeParams params,
            String operator, BooleanSupplier cancellationCheck) {
        this(kbId, documentId, summaryModelProfileId, embeddingModelProfileId, language,
                params, operator, cancellationCheck, null);
    }

    /**
     * 是否已被请求取消（检查器抛出的异常原样上抛，由调用方统一兜底）。
     *
     * @return 已取消返回 true
     */
    public boolean isCancelled() {
        return ObjectUtils.isNotEmpty(cancellationCheck) && cancellationCheck.getAsBoolean();
    }
}
