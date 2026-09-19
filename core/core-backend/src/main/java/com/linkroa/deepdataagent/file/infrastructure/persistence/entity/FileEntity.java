package com.linkroa.deepdataagent.file.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件持久化实体（对应 files 表，仅元数据；内容字节落磁盘目录）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("files")
public class FileEntity extends BaseEntity {

    /** 文件业务 ID（前缀 file_） */
    private String fileId;

    /** 归属用户 ID */
    private Long ownerId;

    /** 文件名 */
    private String filename;

    /** 内容类型（服务端探测，如 text/markdown、application/json） */
    private String mimeType;

    /** 内容字节长度 */
    private Long sizeBytes;

    /** 文件用途（五态契约值：user_upload/tool_output/skill_output/session_resource/agent_output） */
    private String purpose;

    /** 文件状态（ready，上传成功即就绪） */
    private String status;

    /** 是否可直接下载（由 purpose 派生） */
    private Boolean downloadable;

    /** 文件作用域（对应 PG jsonb 列，{id, type}；未关联为 null） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String scope;

    /** 自定义元数据（对应 PG jsonb 列，缺省 {}） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;

    /** 磁盘内容 SHA-256 hex 摘要（一致性校验依据） */
    private String contentSha256;
}
