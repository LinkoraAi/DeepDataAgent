package com.linkroa.deepdataagent.memory.model;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 一次待记录对话的结构化上下文。
 *
 * <p>由 DeepLongMemory 从 AgentScope Msg 列表构建，供提取器生成会话文本、
 * 识别最近用户输入和最近助手回复。</p>
 */
public record ConversationContext(
        String sessionId,
        String userName,
        Instant createdAt,
        List<ConversationMessage> messages
) {

    public String transcript() {
        StringBuilder builder = new StringBuilder();
        for (ConversationMessage message : messages) {
            if (message.text() == null || message.text().isBlank()) {
                continue;
            }
            builder.append("**")
                    .append(displayName(message))
                    .append("**: ")
                    .append(message.text().strip())
                    .append("\n\n");
        }
        return builder.toString().strip();
    }

    public String latestUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage message = messages.get(i);
            if ("user".equalsIgnoreCase(message.role()) && message.text() != null && !message.text().isBlank()) {
                return message.text().strip();
            }
        }
        return "";
    }

    public String latestAssistantText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage message = messages.get(i);
            if ("assistant".equalsIgnoreCase(message.role()) && message.text() != null && !message.text().isBlank()) {
                return message.text().strip();
            }
        }
        return "";
    }

    private static String displayName(ConversationMessage message) {
        if (message.name() != null && !message.name().isBlank()) {
            return message.name();
        }
        if (message.role() == null || message.role().isBlank()) {
            return "unknown";
        }
        return message.role().toLowerCase(Locale.ROOT);
    }

    /**
     * 归一化后的单条对话消息。
     */
    public record ConversationMessage(String name, String role, String text, String timestamp) {
    }
}
