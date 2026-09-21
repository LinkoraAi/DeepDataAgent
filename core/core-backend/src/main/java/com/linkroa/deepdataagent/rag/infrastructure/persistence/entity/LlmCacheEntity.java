package com.linkroa.deepdataagent.rag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * LLM 调用缓存持久化实体（对应 llm_cache 表）。
 * <p>cache_key 列类型为 CHAR(32)，Java 侧固定 32 位 MD5 hex 字符串；
 * 复合唯一键 {@code (kb_id, cache_type, cache_key)} 承担库级隔离与
 * 分类隔离命中检索。</p>
 * <p>RAG BC 彻底物理删除：不继承 BaseEntity、无 is_deleted 列，
 * audit 字段自持并由 {@code RagAuditFieldUtils} 在仓储层显式填充。</p>
 */
@Data
@EqualsAndHashCode
@TableName("llm_cache")
public class LlmCacheEntity implements RagAuditable {

    /** 主键（数据库自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属知识库ID（缓存隔离与按库整清的前置维度） */
    private Long kbId;

    /** 缓存分类（ANSWER/QUERY_REWRITE/KEYWORD_EXTRACT/ENTITY_DESC，对应 CacheType 枚举名） */
    private String cacheType;

    /** 缓存键（32 位 MD5 hex：model + prompt + 归一化参数，本身不含 kb_id） */
    private String cacheKey;

    /** 模型标识 */
    private String model;

    /** 完整提示词 */
    private String prompt;

    /** 模型返回内容 */
    private String response;

    /** 输入+输出 token 总量 */
    private Integer totalTokens;

    /** 创建时间（仓储层插入前显式填充，TIMESTAMPTZ） */
    @TableField("created_at")
    private OffsetDateTime createdAt;

    /** 更新时间（仓储层插入/更新前显式填充，TIMESTAMPTZ） */
    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    /** 创建人（仓储层插入前显式填充，无操作人上下文兜底 system） */
    @TableField("created_by")
    private String createdBy;

    /** 更新人（仓储层插入/更新前显式填充，无操作人上下文兜底 system） */
    @TableField("updated_by")
    private String updatedBy;
}
