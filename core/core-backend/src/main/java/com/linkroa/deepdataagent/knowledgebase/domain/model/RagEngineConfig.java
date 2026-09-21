package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RagEngineType;
import org.apache.commons.lang3.ObjectUtils;

/**
 * RAG 引擎配置值对象：按引擎类型区分解析与分块配置。
 * <p>知识库语言不属于本值对象职责：唯一真相源为
 * {@code knowledge_base.language} 列及其 {@code KnowledgeBase.language} 聚合字段，
 * 本 JSONB 的 {@code "language"} 键已弃用——写入口不再校验、读侧不再解析，存量数据不清理。</p>
 * <p>模型选型不属于本值对象职责：知识库级 LLM（对话 / 多模态 / 实体抽取 / 关键词提取）统一引用见
 * {@link MultiModelConfig}，嵌入模型见 {@link EmbeddingModelConfig}，重排模型见
 * {@link RerankModelConfig}。</p>
 *
 * @param engineType    引擎类型（DOCUMENT_ENGINE / MEDIA_ENGINE）
 * @param parseEngine   解析引擎配置 JSON（按引擎类型多态承载文档 / 媒体解析参数）
 * @param chunkStrategy 分块策略配置 JSON（按引擎类型多态承载文档 / 媒体分块参数）
 */
public record RagEngineConfig(
        RagEngineType engineType,
        String parseEngine,
        String chunkStrategy
) {

    public RagEngineConfig {
        if (ObjectUtils.isEmpty(engineType)) {
            throw new IllegalArgumentException("RAG引擎类型不能为空");
        }
    }
}
