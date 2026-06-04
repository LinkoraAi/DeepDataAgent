package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户模型配置实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_model_config")
public class LlmModelConfigEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private Long templateId;

    private String provider;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Double temperature;

    private Integer isDefault;

    private String description;

    private String createdAt;

    private String updatedAt;

    private Integer isDeleted;
}
