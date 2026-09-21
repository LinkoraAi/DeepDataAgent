package com.linkroa.deepdataagent.knowledgebase.controller.request;

import jakarta.validation.constraints.Size;

/**
 * 更新知识库请求。
 * <p>所有字段均为可选，未传（{@code null}）表示保持原值不变，由应用层执行「空值不覆盖」语义。</p>
 *
 * @param name              知识库名称（最长 128 字符）
 * @param description       知识库描述
 * @param language          知识库语言（可选，落 {@code knowledge_base.language} 列——语言唯一真相源；
 *                          值域为 KbLanguage 十一语言全名、大小写不敏感；未传（null）保持原值不覆盖）
 * @param ragEngineConfig   RAG 引擎配置 JSON
 * @param dedupPolicy       去重策略 JSON
 * @param retrievalStrategy 检索策略 JSON
 * @param embeddingConfig   嵌入模型配置 JSON
 * @param multiModelConfig  多模态模型配置 JSON
 * @param entityTypeConfig  实体类型自定义配置 JSON
 */
public record UpdateKnowledgeBaseRequest(

        @Size(max = 128, message = "知识库名称不能超过128个字符")
        String name,

        String description,

        String language,

        String ragEngineConfig,

        String dedupPolicy,

        String retrievalStrategy,

        String embeddingConfig,

        String multiModelConfig,

        String entityTypeConfig
) {
}
