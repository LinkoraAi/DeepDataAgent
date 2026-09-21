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
 * 抽取缓存归属持久化实体（对应 {@code chunk_extract_cache} 表）。
 * <p>一行即一条「分块 → 缓存行」的使用关系；{@code cache_key} 列为 CHAR(32)，
 * Java 侧固定 32 位 MD5 hex 字符串。落库唯一键
 * {@code (chunk_id, cache_type, cache_key)} 承担登记幂等，
 * 查询索引 {@code (kb_id, cache_type, cache_key)} 承担引用计数判定的扫描面收窄。</p>
 * <p>RAG BC 彻底物理删除：不继承 BaseEntity、无 is_deleted 列，
 * audit 字段自持并由 {@code RagAuditFieldUtils} 在仓储层显式填充。</p>
 */
@Data
@EqualsAndHashCode
@TableName("chunk_extract_cache")
public class ChunkExtractCacheEntity implements RagAuditable {

    /** 主键（数据库自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属知识库ID（与 llm_cache 复合唯一键同源的隔离维度） */
    private Long kbId;

    /** 分块ID（弱引用 chunk.id，归属的引用方） */
    private Long chunkId;

    /** 缓存分类（CacheType 枚举名，抽取与媒体描述为 EXTRACT） */
    private String cacheType;

    /** 缓存键（32 位 MD5 hex，弱引用 llm_cache.cache_key） */
    private String cacheKey;

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
