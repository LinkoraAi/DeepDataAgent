package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预置模型模板实体（无 BaseEntity，模板由系统管理，无 CRUD 审计字段）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_model_template")
public class LlmModelTemplateEntity {

    private Long id;
    private String provider;
    private String modelName;
    private String displayName;
    private String baseUrl;
    private String description;
    private Integer sortOrder;
    private Integer isEnabled;
    private String createdAt;
}
