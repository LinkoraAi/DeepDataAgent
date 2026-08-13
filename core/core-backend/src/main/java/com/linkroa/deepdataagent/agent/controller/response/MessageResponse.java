package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 消息响应 DTO
 * <p>与前端 {@code Message} 类型完全对齐，字段名和类型一一对应。</p>
 *
 * @param id          消息序列号（对应 DialogueMessage.messageNumber）
 * @param sessionId   会话 ID
 * @param dialogueId  对话轮次 ID（用于前端按轮次分组）
 * @param role        消息角色（user / assistant / system / tool）
 * @param type        消息类型（MESSAGE / THINKING / TOOL_CALL / TOOL_RESULT / ERROR），透出 messageType
 * @param content     消息文本内容
 * @param toolCalls   工具调用信息（JSON 字符串，仅 TOOL_CALL 类型时有值）
 * @param toolResult  工具调用结果（JSON 字符串，仅 TOOL_RESULT 类型时有值）
 * @param toolCallId  工具调用 ID（仅 TOOL_CALL / TOOL_RESULT 类型时有值，同一调用两条消息一致，用于调用与结果配对）
 * @param createdAt   消息创建时间
 * @param status      消息状态（COMPLETED / IN_PROGRESS / FAILED）
 */
public record MessageResponse(
    Long id,
    String sessionId,
    Long dialogueId,
    String role,
    String type,
    String content,
    String toolCalls,
    String toolResult,
    String toolCallId,
    String createdAt,
    String status
) {}