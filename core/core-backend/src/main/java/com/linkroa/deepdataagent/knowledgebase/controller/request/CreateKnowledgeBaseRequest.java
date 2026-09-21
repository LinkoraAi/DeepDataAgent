package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库请求。
 * <p>各配置项以 JSON 字符串形态传入，由应用层负责反序列化为领域值对象并校验。</p>
 *
 * @param name              知识库名称（全局唯一，最长 128 字符）
 * @param description       知识库描述（可选）
 * @param language          知识库语言（可选，落 {@code knowledge_base.language} 列——语言唯一真相源；
 *                          值域为 KbLanguage 十一语言全名、大小写不敏感，未传时应用层缺省落 Chinese；
 *                          {@code rag_engine_config} JSONB 的 language 键已弃用）
 * @param ragEngineConfig   RAG 引擎配置 JSON（含解析引擎、分块策略等）
 * @param dedupPolicy       去重策略 JSON
 * @param embeddingConfig   嵌入模型配置 JSON（须包含 modelProfileId）
 * @param multiModelConfig  多模态模型配置 JSON（须包含 modelProfileId）
 * @param retrievalStrategy 检索策略 JSON（可选，未传时由应用层使用默认值）
 * @param entityTypeConfig  实体类型自定义配置 JSON（可选）
 */
public record CreateKnowledgeBaseRequest(

        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 128, message = "知识库名称不能超过128个字符")
        String name,

        String description,

        String language,

        @NotBlank(message = "RAG引擎配置不能为空")
        String ragEngineConfig,

        @NotBlank(message = "去重策略不能为空")
        String dedupPolicy,

        @NotBlank(message = "嵌入模型配置不能为空")
        String embeddingConfig,

        @NotBlank(message = "多模态模型配置不能为空")
        String multiModelConfig,

        String retrievalStrategy,

        String entityTypeConfig
) {
}
