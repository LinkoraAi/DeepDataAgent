package com.linkroa.deepdataagent.agent.application.dto;

/**
 * 消息 DTO
 * <p>应用层返回的消息对象，由控制器层转换为 {@link com.linkroa.deepdataagent.agent.controller.response.MessageResponse}。</p>
 *
 * @param id         消息序列号
 * @param sessionId  会话 ID
 * @param dialogueId 对话轮次 ID（用于前端按轮次分组）
 * @param role       消息角色（user / assistant / system）
 * @param content    消息文本内容
 * @param toolCalls  工具调用信息（JSON 字符串）
 * @param toolResult 工具调用结果（JSON 字符串）
 * @param createdAt  消息创建时间（已格式化）
 */
public record MessageDTO(
        Long id,
        String sessionId,
        Long dialogueId,
        String role,
        String content,
        String toolCalls,
        String toolResult,
        String createdAt
) {
}