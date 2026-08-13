package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 模型配置实体（合并原 provider + info + config 三张表）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("model_config")
public class ModelConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提供商展示名称（如 OpenAI） */
    private String providerDisplayName;

    /** 提供商标识（如 openai） */
    private String providerName;

    /** 模型 ID（如 gpt-4o） */
    private String modelId;

    /** API 地址 */
    private String apiUrl;

    /** API 密钥（加密存储） */
    private String apiKey;

    /** 是否默认模型（1=是，0=否） */
    private Integer isDefault;

    /** 是否启用（1=启用，0=停用） */
    private Integer isEnabled;

    /** 排序权重 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdTime;

    /** 更新时间 */
    private LocalDateTime updatedTime;

    /** 逻辑删除标记（1=已删除，0=未删除） */
    private Integer isDeleted;
}