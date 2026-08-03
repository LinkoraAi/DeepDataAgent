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
 * 模型信息实体（合并原 provider + info + config 三张表）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("agent_model_info")
public class AgentModelInfoEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerDisplayName;

    private String providerName;

    private String modelId;

    private String apiUrl;

    private String apiKey;

    private Integer isDefault;

    private Integer isEnabled;

    private Integer sortOrder;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    private Integer isDeleted;
}
