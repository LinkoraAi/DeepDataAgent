package com.linkroa.deepdataagent.memory.extractor;

import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ConversationContext.ConversationMessage;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;
import com.linkroa.deepdataagent.memory.util.MemoryText;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 轻量记忆提取器。
 *
 * <p>当前实现不依赖 LLM，使用明确的中文/英文信号词从对话中提取 episodic、
 * semantic 和 skills 记忆。后续接入 LLM 提取时，可以保持输出模型不变并替换该组件内部策略。</p>
 */
@Component
public class SimpleMemoryExtractor {

    public List<ExtractedMemory> extractAndClassify(ConversationContext context) {
        List<ExtractedMemory> memories = new ArrayList<>();
        String transcript = context.transcript();
        if (!transcript.isBlank()) {
            // 每次有效对话至少保留一条 episodic 记忆，后续可以从原始上下文中追溯细节。
            memories.add(episodicMemory(context, transcript));
        }

        for (ConversationMessage message : context.messages()) {
            if (!"user".equalsIgnoreCase(message.role()) || message.text() == null || message.text().isBlank()) {
                continue;
            }
            // semantic 记忆只从用户侧明确表达中提取，避免把助手建议误写成用户事实。
            ExtractedMemory semantic = semanticMemory(context, message.text());
            if (semantic != null) {
                memories.add(semantic);
            }
        }

        ExtractedMemory skill = skillMemory(context);
        if (skill != null) {
            memories.add(skill);
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
        boolean fact = containsAny(normalized, "项目", "环境", "数据库", "接口", "服务", "规范", "约定", "版本", "账号");
        boolean decision = containsAny(normalized, "决定", "采用", "放弃", "选择", "约定");

        if (!explicitRemember && !preference && !fact && !decision) {
            return null;
        }

        // 用户画像进入 USER.md；客观事实、规则和决策进入 MEMORY.md，保持 Hermes 式职责分离。
        String subCategory;
        String layerTarget;
        if (preference) {
            subCategory = "preference";
            layerTarget = "USER.md";
        } else if (decision) {
            subCategory = "rule";
            layerTarget = "MEMORY.md";
        } else {
            subCategory = "fact";
            layerTarget = "MEMORY.md";
        }

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

    private ExtractedMemory skillMemory(ConversationContext context) {
        String user = context.latestUserText().toLowerCase(Locale.ROOT);
        String assistant = context.latestAssistantText();
        if (assistant.isBlank() || assistant.length() < 120) {
            return null;
        }
        // skills 只在“怎么做/流程/方案”类问题中沉淀，降低普通闲聊生成技能文件的概率。
        boolean proceduralSignal = containsAny(user, "流程", "步骤", "最佳实践", "方案", "模式", "怎么做", "如何");
        if (!proceduralSignal) {
            return null;
        }

        String title = MemoryText.firstNonBlank(context.latestUserText(), "自动技能", "自动技能");
        String content = "适用场景：" + shorten(context.latestUserText(), 180) + "\n\n"
                + "执行经验：\n" + assistant.strip();
        return new ExtractedMemory(
                newMemoryId(),
                "skills",
                "skill",
                shorten(title, 80),
                content,
                0.68,
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
