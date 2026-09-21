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
 * 图谱关系边持久化实体（对应 relation_edge_graph 表）。
 * <p>图边保留原始方向，唯一键 {@code (kb_id, source_name, target_name)}；
 * properties 列以 JSON 文本承载（RelationProperties 序列化结果）。</p>
 * <p>RAG BC 彻底物理删除：不继承 BaseEntity、无 is_deleted 列，
 * audit 字段自持并由 {@code RagAuditFieldUtils} 在仓储层显式填充。</p>
 */
@Data
@EqualsAndHashCode
@TableName("relation_edge_graph")
public class RelationEdgeGraphEntity implements RagAuditable {

    /** 主键（数据库自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属知识库ID */
    private Long kbId;

    /** 源实体名称（弱引用 entity_node_graph.entity_name） */
    private String sourceName;

    /** 目标实体名称（弱引用 entity_node_graph.entity_name） */
    private String targetName;

    /** 关系属性 JSON 文本（RelationProperties：weight/description/keywords/sourceIds/filePaths） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String properties;

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
