package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型配置持久化实体（对应 model_profile 表）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_profile")
public class ModelProfileEntity extends BaseEntity {

    /** 业务ID */
    private String profileId;
    /** 显示名称 */
    private String displayName;
    /** 描述 */
    private String description;
    /** API格式（AGENTSCOPE / OPENAI / BAILIAN / OTHER） */
    private String apiFormat;
    /** API端点URL */
    private String apiEndpointUrl;
    /** 模型名称 */
    private String modelName;
    /** 加密后的凭证 */
    private String encryptedCredential;
    /** 模型系列 */
    private String modelSeries;
    /** 输入上下文窗口大小 */
    private Integer contextWindowInput;
    /** 输出上下文窗口大小 */
    private Integer contextWindowOutput;
    /** 工具调用轮次上限 */
    private Integer toolCallRounds;
    /** 模型类型码值（1=CHAT 2=EMBEDDING） */
    private Integer modelType;
    /** 向量维度 */
    private Integer vectorDimension;
    /** 状态（ENABLED / DISABLED） */
    private String status;
}