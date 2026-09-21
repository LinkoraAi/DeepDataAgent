package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence.KbAuditable;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 切片向量持久化实体（对应 chunk_vector 表）。
 * <p>与 chunk 表 1:1，独立承载 HNSW 近邻索引以隔离主表写放大。</p>
 * <p>chunk_vector 字段以向量字面量（形如 "[0.1,0.2]"）承载，写入时由 SQL 显式
 * {@code ::vector} 转换，避免为批量写入额外引入 TypeHandler。</p>
 * <p>彻底物理删体系：不继承 {@code shared.BaseEntity}、
 * 无 is_deleted 列（墓碑行会永久占据 HNSW 近邻槽位，唯有物理删行），audit 字段自持并由
 * {@code KbAuditFieldUtils} 在 Repository 层显式填充。</p>
 */
@Data
@TableName("chunk_vector")
public class ChunkVectorEntity implements KbAuditable {

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

    /** 所属知识库ID（冗余，支持库级向量检索过滤） */
    private Long kbId;

    /** 所属文档ID（冗余，支持按文档过滤重建） */
    private Long documentId;

    /** 分块ID，弱引用 chunk.id，1:1 */
    private Long chunkId;

    /** 块内容嵌入向量字面量，形如 "[0.1,0.2,...]" */
    private String chunkVector;

    /**
     * 检索侧临时列：余弦相似度分（1 - 余弦距离）。
     * <p>仅供 VECTOR 通道只读检索结果承载，非持久化列，
     * {@code @TableField(exist = false)} 使其不参与增删改与常规查询。</p>
     */
    @TableField(exist = false)
    private Double score;
}
