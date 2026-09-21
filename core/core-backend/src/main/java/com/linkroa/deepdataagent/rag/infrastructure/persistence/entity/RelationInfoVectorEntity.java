package com.linkroa.deepdataagent.rag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.RagAuditable;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 关系向量持久化实体（对应 relation_info_vector 表，与 relation_edge_graph 1:1）。
 * <p>向量身份使用 sorted 双向端点对，写入方统一按字典序方向落库；
 * content_vector / chunk_ids 承载方式同 {@link EntityInfoVectorEntity}。</p>
 * <p>RAG BC 彻底物理删除：不继承 BaseEntity、无 is_deleted 列（HNSW 索引不容墓碑），
 * audit 字段自持并由 {@code RagAuditFieldUtils} 在仓储层显式填充。</p>
 */
@Data
@EqualsAndHashCode
@TableName("relation_info_vector")
public class RelationInfoVectorEntity implements RagAuditable {

    /** 主键（数据库自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属知识库ID */
    private Long kbId;

    /** 源实体名称（向量侧统一字典序方向） */
    private String sourceName;

    /** 目标实体名称（向量侧统一字典序方向） */
    private String targetName;

    /** 向量化内容（keywords + 端点 + 描述） */
    private String content;

    /** 关系向量字面量，形如 "[0.1,0.2,...]"；可为 null */
    private String contentVector;

    /** 关联分块ID列表 JSON 文本（弱引用 chunk.id，merge 时累积） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String chunkIds;

    /** 来源文件路径 */
    private String filePath;

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
