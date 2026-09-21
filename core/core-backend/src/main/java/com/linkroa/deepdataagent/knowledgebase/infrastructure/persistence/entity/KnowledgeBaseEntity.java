package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditable;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 知识库持久化实体（对应 knowledge_base 表）。
 * <p>库级配置统一以 JSONB 文本承载，领域侧按值对象解析。</p>
 * <p>彻底物理删体系：不继承 {@code shared.BaseEntity}、
 * 无 is_deleted 列，audit 字段自持并由 {@code KbAuditFieldUtils} 在 Repository 层显式填充。</p>
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBaseEntity implements KbAuditable {

    /** 主键（数据库自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 创建时间（TIMESTAMPTZ，插入前由 KbAuditFieldUtils 显式填充） */
    @TableField("created_at")
    private OffsetDateTime createdAt;

    /** 更新时间（TIMESTAMPTZ，插入/更新前由 KbAuditFieldUtils 显式填充） */
    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    /** 创建人（插入前由 KbAuditFieldUtils 显式填充，无操作人上下文回落 system） */
    @TableField("created_by")
    private String createdBy;

    /** 更新人（插入/更新前由 KbAuditFieldUtils 显式填充，无操作人上下文回落 system） */
    @TableField("updated_by")
    private String updatedBy;

    /** 知识库名称（全表唯一：uk_kb_name 普通唯一索引，物理删行即释放名称） */
    private String name;

    /** 知识库描述 */
    private String description;

    /**
     * 知识库语言（{@code knowledge_base.language} 列）：知识库语言的唯一真相源。
     * <p>值域为 {@code KbLanguage} 十一个语言全名（大小写不敏感归一，存量值如 ENGLISH 读取侧归入 English）；
     * 缺省语义 = Chinese（应用层创建时恒显式写入，不依赖列库默认值 'ENGLISH'）。
     * {@code rag_engine_config} JSONB 的 language 键已弃用，与本列无关。</p>
     */
    private String language;

    /** 生命周期状态：ACTIVE/DELETING/DELETE_FAILED（DELETED 已移除，收口为行物理删除、行缺失即已删除） */
    private String lifecycleStatus;

    /**
     * 删除失败留痕（{@code knowledge_base.error_message} 列）：仅 DELETE_FAILED 态携带
     * 失败步骤与原因摘要（形如 {@code [KB-CLEANUP] step=…}），重删推回 DELETING 时清除。
     */
    private String errorMessage;

    /** RAG 引擎配置 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String ragEngineConfig;

    /** 去重策略 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String dedupPolicy;

    /** 检索策略 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String retrievalStrategy;

    /** 嵌入模型配置 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String embeddingConfig;

    /** 多模态模型配置 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String multiModelConfig;

    /** 实体类型自定义配置 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String entityTypeConfig;
}
