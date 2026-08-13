package com.linkroa.deepdataagent.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linkroa.deepdataagent.shared.util.PostgresJsonbTypeHandler;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 对话轮次实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("dialogue")
public class DialogueEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID */
    private String sessionId;

    /** 用户问题 */
    private String userQuestion;

    /** 消息列表（DialogueMessage JSON 数组序列化后的文本，对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String messages;

    /** 对话状态（PENDING/RUNNING/COMPLETED/FAILED/CANCELLED 等） */
    private String status;

    /** LLM 调用统计信息（预留：调用次数/token 用量/耗时，对应 PG jsonb 列） */
    @TableField(typeHandler = PostgresJsonbTypeHandler.class)
    private String metadata;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer isDeleted;
}
