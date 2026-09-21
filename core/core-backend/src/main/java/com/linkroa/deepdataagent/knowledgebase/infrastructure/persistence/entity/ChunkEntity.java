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
 * 切片持久化实体（对应 chunk 表）。
 * <p>主表仅承载业务字段，向量与全文分词分别由 chunk_vector / chunk_tsv 独立表承载。</p>
 * <p>彻底物理删体系：不继承 {@code shared.BaseEntity}、
 * 无 is_deleted 列，audit 字段自持并由 {@code KbAuditFieldUtils} 在 Repository 层显式填充。</p>
 */
@Data
@TableName("chunk")
public class ChunkEntity implements KbAuditable {

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

    /** 所属知识库ID（冗余提升列，加速库级检索） */
    private Long kbId;

    /** 所属文档ID，弱引用 document.id */
    private Long documentId;

    /** 块在文档内的序号 */
    private Integer sequence;

    /** 块的 token 数量 */
    private Integer tokens;

    /** 块内容（套模板后的最终文本，PG text 列） */
    private String chunkContent;

    /** 多模态原始信息 JSON（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String originalItem;

    /** 内容形态：TEXT/IMAGE/TABLE/EQUATION/GENERIC */
    private String chunkContentType;

    /** 来源文件名（冗余，便于引用展示） */
    private String sourceFileName;

    /** 多模态（图片）对象存储引用 JSON：{objectKey}（对应 PG jsonb 列；桶概念已退役）；非多模态为空 */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String s3File;

    /** 分块来源标识：PARSED=解析产生 / MANUAL=人工新增（写入即定，编辑等操作不改写；无来源列兜底解析产生） */
    private String sourceType;
}
