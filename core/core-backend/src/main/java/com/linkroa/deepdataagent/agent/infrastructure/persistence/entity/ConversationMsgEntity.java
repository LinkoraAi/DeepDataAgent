package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话消息历史实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("conversation_msg")
public class ConversationMsgEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String role;

    private String content;

    private String toolCalls;

    private String toolResult;

    private String metadata;

    private String createdAt;
}
