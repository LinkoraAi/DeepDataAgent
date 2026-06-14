package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 会话实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("agent_session")
public class AgentSessionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String title;

    private Long datasourceId;

    private Long modelConfigId;

    private String status;

    private Integer messageCount;

    private String lastMessageAt;

    private String createdAt;

    private String updatedAt;

    private String closedAt;

    @TableLogic
    private Integer isDeleted;
}
