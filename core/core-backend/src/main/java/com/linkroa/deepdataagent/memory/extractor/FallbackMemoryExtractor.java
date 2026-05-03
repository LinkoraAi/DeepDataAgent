package com.linkroa.deepdataagent.memory.extractor;

import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ConversationContext.ConversationMessage;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;
import com.linkroa.deepdataagent.memory.util.MemoryText;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 降级记忆提取器。
 *
 * <p>当 LLM 不可用时作为后备方案，使用基础关键词匹配提取 episodic 记忆，
 * 确保记忆系统在 LLM 故障时仍能提供基础功能。</p>
 */
public class FallbackMemoryExtractor implements MemoryExtractor {

    @Override
    public List<ExtractedMemory> extractAndClassify(ConversationContext context) {
        List<ExtractedMemory> memories = new ArrayList<>();

        String transcript = context.transcript();
        if (!transcript.isBlank()) {
            memories.add(episodicMemory(context, transcript));
        }

        for (ConversationMessage message : context.messages()) {
            if (!"user".equalsIgnoreCase(message.role()) || message.text() == null || message.text().isBlank()) {
                continue;
            }
            ExtractedMemory semantic = semanticMemory(context, message.text());
            if (semantic != null) {
                memories.add(semantic);
            }
        }

        return memories;
    }

    private ExtractedMemory episodicMemory(ConversationContext context, String transcript) {
        String latestUserText = context.latestUserText();
        String title = MemoryText.firstNonBlank(latestUserText, context.sessionId(), "会话记录");
        StringBuilder content = new StringBuilder();
        content.append("## 会话摘要\n");
        content.append("- 用户最近问题：").append(MemoryText.firstNonBlank(latestUserText, "", "未识别")).append("\n");
        String assistantText = context.latestAssistantText();
        if (!assistantText.isBlank()) {
            content.append("- 最近回复要点：").append(shorten(assistantText, 260)).append("\n");
        }
        content.append("\n## 原始对话（关键片段）\n");
        content.append(transcript);

        return new ExtractedMemory(
                newMemoryId(),
                "episodic",
                "event",
                shorten(title, 80),
                content.toString(),
                0.6,
                context.createdAt(),
                context.sessionId()
        );
    }

    private ExtractedMemory semanticMemory(ConversationContext context, String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        boolean explicitRemember = containsAny(normalized, "记住", "请记住", "帮我记", "以后记得", "remember");
        boolean preference = containsAny(normalized, "偏好", "喜欢", "不喜欢", "倾向", "习惯", "以后", "不要", "优先");

        if (!explicitRemember && !preference) {
            return null;
        }

        String subCategory = preference ? "preference" : "fact";
        String layerTarget = "preference".equals(subCategory) ? "USER.md" : "MEMORY.md";
        String content = "来源会话：" + context.sessionId() + "\n\n" + text.strip();
        double importance = explicitRemember ? 0.9 : 0.72;

        return new ExtractedMemory(
                newMemoryId(),
                "semantic",
                subCategory,
                shorten(text, 80),
                content + "\n\n目标摘要文件：" + layerTarget,
                importance,
                Instant.now(),
                context.sessionId()
        );
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String shorten(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip().replaceAll("\\s+", " ");
        if (stripped.length() <= maxLength) {
            return stripped;
        }
        return stripped.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String newMemoryId() {
        return "mem-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
