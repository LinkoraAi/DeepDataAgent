package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.dto.MessageDTO;
import com.linkroa.deepdataagent.agent.domain.model.DialogueContent;
import com.linkroa.deepdataagent.agent.domain.model.DialogueMessage;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageRole;
import com.linkroa.deepdataagent.agent.domain.valueobject.MessageType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MessageDTO 组装器
 * <p>将领域模型 {@link DialogueMessage} 转换为应用层 DTO {@link MessageDTO}，
 * 根据角色映射不同字段，由控制器层进一步转换为 {@link com.linkroa.deepdataagent.agent.controller.response.MessageResponse}。</p>
 */
public final class MessageDTOAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MessageDTOAssembler() {
    }

    /**
     * 将 DialogueMessage 领域模型转换为 MessageDTO
     *
     * @param message    对话消息
     * @param sessionId  会话 ID
     * @param dialogueId 对话轮次 ID
     * @return 消息 DTO
     */
    public static MessageDTO toDTO(DialogueMessage message, String sessionId, Long dialogueId) {
        if (message == null) {
            return null;
        }

        DialogueContent content = message.getContent();
        MessageRole role = message.getRole();
        MessageType type = message.getMessageType();

        // 根据 MessageType 映射字段，MessageRole 决定角色字符串
        String textContent = "";
        String toolCalls = null;
        String toolResult = null;
        String toolCallId = null;

        if (content != null && type != null) {
            switch (type) {
                case TOOL_CALL -> {
                    // TOOL_CALL: title 为工具名，input 为入参；结果由独立的 TOOL_RESULT 消息承载
                    toolCalls = content.title();
                    textContent = content.input() != null ? content.input() : "";
                    toolCallId = content.toolCallId();
                }
                case TOOL_RESULT -> {
                    // TOOL_RESULT: title 为工具名，result 为返回结果 JSON
                    toolCalls = content.title();
                    toolResult = content.result();
                    toolCallId = content.toolCallId();
                }
                case THINKING -> {
                    // THINKING: result 为思考过程文本
                    textContent = content.result();
                }
                case MESSAGE -> {
                    // MESSAGE: 纯文本消息（含 ASSISTANT 角色的最终回复/报告）
                    textContent = content.result();
                }
                default -> textContent = content.result();
            }
        }

        // THINKING 消息统一映射为 thinking 角色（内部角色为 ASSISTANT），便于前端历史回放识别思考块
        String roleStr = (type == MessageType.THINKING) ? "thinking"
                : (role != null ? role.name().toLowerCase() : null);
        return new MessageDTO(
                message.getMessageNumber(),
                sessionId,
                dialogueId,
                roleStr,
                type != null ? type.name() : null,
                textContent,
                toolCalls,
                toolResult,
                toolCallId,
                formatDateTime(message.getStartTime()),
                message.getStatus() != null ? message.getStatus().name() : null
        );
    }

    /**
     * 时间格式化
     */
    private static String formatDateTime(LocalDateTime dt) {
        return dt == null ? null : dt.format(FORMATTER);
    }
}