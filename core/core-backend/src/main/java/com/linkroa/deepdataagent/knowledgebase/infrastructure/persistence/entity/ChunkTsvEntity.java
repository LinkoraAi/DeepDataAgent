package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditable;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 切片全文检索持久化实体（对应 chunk_tsv 表）。
 * <p>与 chunk 表 1:1，承载 tsvector 及 GIN 索引。</p>
 * <p>tsvector 列不再由应用侧预生成字面量：批量插入 SQL 内以
 * {@code to_tsvector(配置名, chunkContent)} 对切片原文现算，
 * 本实体不声明 content_tsv 持久字段。</p>
 * <p>彻底物理删体系：不继承 {@code shared.BaseEntity}、
 * 无 is_deleted 列，audit 字段自持并由 {@code KbAuditFieldUtils} 在 Repository 层显式填充。</p>
 */
@Data
@TableName("chunk_tsv")
public class ChunkTsvEntity implements KbAuditable {

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

    /** 所属知识库ID（冗余，支持库级全文过滤） */
    private Long kbId;

    /** 所属文档ID（冗余） */
    private Long documentId;

    /** 分块ID，弱引用 chunk.id，1:1 */
    private Long chunkId;

    /**
     * 非持久字段：切片原文（批量插入入参）。
     * <p>仅供 {@code insertBatch} 的 {@code to_tsvector} 现算使用，
     * {@code @TableField(exist = false)} 使其不参与常规增删改查。</p>
     */
    @TableField(exist = false)
    private String chunkContent;

    /**
     * 检索侧临时列：BM25 相关度分（ts_rank_cd 输出）。
     * <p>仅供 BM25 通道只读检索结果承载，非持久化列，
     * {@code @TableField(exist = false)} 使其不参与增删改与常规查询。</p>
     */
    @TableField(exist = false)
    private Double score;
}
