package com.linkroa.deepdataagent.agent.controller.response;

import com.linkroa.deepdataagent.agent.domain.model.DialogueContent;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MessageResponse 组装器
 * <p>将领域模型 {@link DialogueMessage} 转换为前端对齐的 {@link MessageResponse}，
 * 根据角色映射不同字段。</p>
 */
public final class MessageResponseAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MessageResponseAssembler() {
    }

    /**
     * 将 DialogueMessage 领域模型转换为 MessageResponse
     *
     * @param message    对话消息
     * @param sessionId  会话 ID
     * @param dialogueId 对话轮次 ID
     * @return 消息响应 DTO
     */
    public static MessageResponse toResponse(DialogueMessage message, String sessionId, Long dialogueId) {
        if (message == null) return null;

        DialogueContent content = message.getContent();
        MessageRole role = message.getRole();
        MessageType type = message.getMessageType();

        // 根据 MessageType 映射字段，MessageRole 决定角色字符串
        String textContent = "";
        String toolCalls = null;
        String toolResult = null;

        if (content != null && type != null) {
            switch (type) {
                case TOOL_CALL -> {
                    // TOOL_CALL: title 为工具名，input 为入参，result 为结果（合并后同一条消息携带）
                    toolCalls = content.title();
                    textContent = content.input() != null ? content.input() : "";
                    toolResult = content.result();
                }
                case TOOL_RESULT -> {
                    // TOOL_RESULT: title 为工具名，result 为返回结果 JSON
                    toolCalls = content.title();
                    toolResult = content.result();
                }
                case THINKING -> {
                    // THINKING: result 为思考过程文本
                    textContent = content.result();
                }
                case MESSAGE -> {
                    // MESSAGE: 根据角色映射
                    if (role != MessageRole.ASSISTANT) {
                        textContent = content.result();
                    }
                }
                default -> textContent = content.result();
            }
        }

        return new MessageResponse(
                message.getSequenceNumber(),
                sessionId,
                dialogueId,
                role != null ? role.name().toLowerCase() : null,
                textContent,
                toolCalls,
                toolResult,
                formatDateTime(message.getStartTime())
        );
    }

    /**
     * 时间格式化
     */
    private static String formatDateTime(LocalDateTime dt) {
        return dt == null ? null : dt.format(FORMATTER);
    }
}