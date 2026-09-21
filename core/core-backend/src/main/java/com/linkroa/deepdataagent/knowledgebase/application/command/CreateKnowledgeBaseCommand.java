package com.linkroa.deepdataagent.knowledgebase.application.command;

/**
 * 创建知识库命令。
 * <p>各配置项以 JSON 字符串承载，由控制层完成结构校验后传入，应用层不再解析其内部结构。</p>
 *
 * @param name              知识库名称（全局唯一，不超过 128 字符）
 * @param description       知识库描述
 * @param language          知识库语言（可选，落 {@code knowledge_base.language} 列——语言唯一真相源；
 *                          空白 / 缺省时聚合层显式落 Chinese，值域校验在应用层完成）
 * @param ragEngineConfig   RAG 解析引擎配置 JSON
 * @param dedupPolicy       文件去重策略 JSON
 * @param retrievalStrategy 检索策略 JSON
 * @param embeddingConfig   嵌入模型配置 JSON
 * @param multiModelConfig  多模态模型配置 JSON
 * @param entityTypeConfig  实体类型自定义配置 JSON
 */
public record CreateKnowledgeBaseCommand(
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
