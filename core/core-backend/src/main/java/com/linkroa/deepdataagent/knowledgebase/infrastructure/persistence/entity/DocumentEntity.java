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
 * 文档持久化实体（对应 document 表）。
 * <p>源文件信息、对象存储引用、分块策略以 JSONB 文本承载；
 * 判重内容哈希为独立一等列（{@code varchar(64)}），文件名判重轴直接取 {@code file_name} 列，
 * 不再在哈希载体里冗余承载。</p>
 * <p>彻底物理删体系：不继承 {@code shared.BaseEntity}、
 * 无 is_deleted 列，audit 字段自持并由 {@code KbAuditFieldUtils} 在 Repository 层显式填充。</p>
 */
@Data
@TableName("document")
public class DocumentEntity implements KbAuditable {

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

    /** 所属知识库ID，弱引用 knowledge_base.id */
    private Long kbId;

    /** 文件名 */
    private String fileName;

    /** 文件格式：FileType 枚举名（PDF/DOC/DOCX/...） */
    private String fileType;

    /** 文档处理状态：PENDING/PROCESSING/PROCESSED/FAILED/DELETING/DELETE_FAILED（DELETED 已移除，收口为行物理删除） */
    private String status;

    /** 处理失败原因：FAILED 时有值，其余状态为 NULL */
    private String errorMessage;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 分块数量 */
    private Integer chunkCount;

    /** 源文件关键信息 JSON（按类型多态，对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String sourceFileProfile;

    /** 导入方式：UPLOAD */
    private String importType;

    /** 源文件对象存储引用 JSON：{objectKey}（对应 PG jsonb 列；桶概念已退役） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String s3File;

    /**
     * 判重内容哈希（对应 PG varchar(64) 一等列 {@code file_content_hash}，非 JSONB）。
     * <p>口径定版（与生产者 {@code DocumentDedupService#sha256Hex}、预检入参校验镜像一致）：
     * 服务端对<b>上传的原始文件字节</b>计算 SHA-256，输出<b>小写十六进制、恒 64 字符</b>；
     * 该值唯一用途是判重轴，MUST NOT 用作文件完整性校验、秒传或解析产物指纹。
     * 文件名判重轴不在此冗余承载，直接取 {@code file_name} 列。</p>
     */
    private String fileContentHash;

    /** 文档级分块策略 JSON，NULL 表示继承库级（对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String chunkStrategy;
}
