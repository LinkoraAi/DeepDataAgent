package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 更新知识库配置命令。
 * <p>字段为空（null / 空白）表示保持原值不变，非空表示覆盖为新值。
 * 嵌入模型配置一旦创建即锁定，此处仍透传以便控制层按需拦截。</p>
 *
 * @param id                知识库ID
 * @param name              知识库名称（全局唯一，不超过 128 字符）
 * @param description       知识库描述
 * @param language          知识库语言（可选，落 {@code knowledge_base.language} 列——语言唯一真相源；
 *                          null / 空白 = 不覆盖保持原值，值域校验在应用层完成）
 * @param ragEngineConfig   RAG 解析引擎配置 JSON
 * @param dedupPolicy       文件去重策略 JSON
 * @param retrievalStrategy 检索策略 JSON
 * @param embeddingConfig   嵌入模型配置 JSON
 * @param multiModelConfig  多模态模型配置 JSON
 * @param entityTypeConfig  实体类型自定义配置 JSON
 */
public record UpdateKnowledgeBaseCommand(
        Long id,
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
