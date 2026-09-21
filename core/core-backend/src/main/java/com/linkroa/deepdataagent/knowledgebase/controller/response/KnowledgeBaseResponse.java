package com.linkroa.deepdataagent.knowledgebase.controller.response;

import java.time.OffsetDateTime;

/**
 * 知识库响应 DTO。
 * <p>领域模型中的枚举字段以枚举名字符串对外暴露，避免前端依赖后端枚举类型。</p>
 *
 * @param id                主键
 * @param name              知识库名称
 * @param description       描述
 * @param language          知识库语言（{@code knowledge_base.language} 列原值回显——语言唯一真相源；
 *                          值域为 KbLanguage 十一语言全名，存量大小写变体按列原样回显）
 * @param lifecycleStatus   生命周期状态（ACTIVE/DELETING/DELETE_FAILED；已删除库行物理缺失，不再对外暴露）
 * @param errorMessage      删除失败留痕（{@code knowledge_base.error_message} 列原值）：仅 DELETE_FAILED 态
 *                          携带清退失败步骤摘要（形如 {@code [KB-CLEANUP] step=…}），供前端提示
 *                          「知识库不可用，请重新执行删除」；其余状态为空白
 * @param ragEngineConfig   RAG 引擎配置 JSON
 * @param dedupPolicy       去重策略 JSON
 * @param retrievalStrategy 检索策略 JSON
 * @param embeddingConfig   嵌入模型配置 JSON
 * @param multiModelConfig  多模态模型配置 JSON
 * @param entityTypeConfig  实体类型自定义配置 JSON
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record KnowledgeBaseResponse(
        Long id,
        String name,
        String description,
        String language,
        String lifecycleStatus,
        String errorMessage,
        String ragEngineConfig,
        String dedupPolicy,
        String retrievalStrategy,
        String embeddingConfig,
        String multiModelConfig,
        String entityTypeConfig,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
