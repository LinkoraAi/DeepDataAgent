package com.linkroa.deepdataagent.memory.extractor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ConversationContext.ConversationMessage;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 基于 LLM 的记忆提取器。
 *
 * <p>使用 AgentScope ChatModel 调用大语言模型，通过结构化 Prompt 引导 LLM
 * 从对话中提取 episodic、semantic 和 skills 记忆，输出 JSON 格式结果。</p>
 */
public class LLMMemoryExtractor implements MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LLMMemoryExtractor.class);

    private static final String SYSTEM_PROMPT = """
            你是一个专业的对话记忆提取专家。请分析以下对话内容，提取并分类长期记忆。

            ## 记忆类型定义

            ### episodic（情景记忆）
            - 记录对话事件的概要
            - 每次对话必须有且仅有一条
            - 包含用户问题和助手回复的要点

            ### semantic（语义记忆）
            - 用户偏好、习惯、态度（subCategory: preference）
            - 客观事实：项目、环境、技术栈（subCategory: fact）
            - 决策和约定（subCategory: rule）
            - 只有在用户明确表达时才提取

            ### skills（技能记忆）
            - 操作流程、最佳实践
            - 仅在助手回复超过 100 字且包含具体步骤时提取（subCategory: skill）

            ## 输出格式
            请输出 JSON 数组，每个元素包含：
            {
              "layer": "episodic|semantic|skills",
              "subCategory": "event|preference|fact|rule|skill",
              "title": "简短标题（不超过80字）",
              "content": "详细内容",
              "importance": 0.0-1.0的小数,
              "reasoning": "提取理由"
            }

            ## 重要性评分指南
            - 0.9-1.0: 用户明确要求记住的关键信息
            - 0.7-0.9: 重要偏好、技术决策
            - 0.5-0.7: 普通事实、一般技能
            - 0.3-0.5: 临时性信息

            只输出 JSON 数组，不要任何其他内容。
            """;

    private final ChatModelBase chatModel;
    private final ObjectMapper objectMapper;

    public LLMMemoryExtractor(ChatModelBase chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<ExtractedMemory> extractAndClassify(ConversationContext context) {
        try {
            String conversationText = buildConversationText(context);
            List<LlmMemoryCandidate> candidates = callLlm(conversationText);
            return convertToExtractedMemories(context, candidates);
        } catch (Exception e) {
            log.warn("LLM 记忆提取失败，返回空列表: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private String buildConversationText(ConversationContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("会话ID: ").append(context.sessionId()).append("\n");
        sb.append("时间: ").append(context.createdAt()).append("\n\n");
        sb.append("## 对话内容\n\n");

        for (ConversationMessage message : context.messages()) {
            if (message.text() == null || message.text().isBlank()) {
                continue;
            }
            String role = message.role() != null ? message.role().toLowerCase(Locale.ROOT) : "unknown";
            sb.append("**").append(role).append("**: ").append(message.text().strip()).append("\n\n");
        }

        return sb.toString().strip();
    }

    private List<LlmMemoryCandidate> callLlm(String conversationText) throws JsonProcessingException {
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .textContent(conversationText)
                .build();

        List<Msg> messages = List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent(SYSTEM_PROMPT)
                        .build(),
                userMsg
        );

        Msg response = chatModel.call(messages).block();

        if (response == null || response.getTextContent() == null) {
            log.warn("LLM 返回空响应");
            return Collections.emptyList();
        }

        String jsonText = response.getTextContent().strip();
        jsonText = extractJsonArray(jsonText);

        return objectMapper.readValue(jsonText, new TypeReference<List<LlmMemoryCandidate>>() {});
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        throw new IllegalArgumentException("LLM 返回内容不包含有效 JSON 数组");
    }

    private List<ExtractedMemory> convertToExtractedMemories(ConversationContext context, List<LlmMemoryCandidate> candidates) {
        List<ExtractedMemory> memories = new ArrayList<>();

        for (LlmMemoryCandidate candidate : candidates) {
            if (!isValidCandidate(candidate)) {
                continue;
            }

            memories.add(new ExtractedMemory(
                    newMemoryId(),
                    candidate.layer,
                    candidate.subCategory,
                    truncate(candidate.title, 80),
                    candidate.content,
                    clampImportance(candidate.importance),
                    context.createdAt(),
                    context.sessionId()
            ));
        }

        return memories;
    }

    private boolean isValidCandidate(LlmMemoryCandidate candidate) {
        if (candidate.layer == null || candidate.layer.isBlank()) {
            return false;
        }
        if (candidate.subCategory == null || candidate.subCategory.isBlank()) {
            return false;
        }
        if (candidate.title == null || candidate.title.isBlank()) {
            return false;
        }
        if (candidate.content == null || candidate.content.isBlank()) {
            return false;
        }
        if (candidate.importance == null) {
            return false;
        }
        return true;
    }

    private double clampImportance(Double importance) {
        if (importance == null) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, importance));
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip().replaceAll("\\s+", " ");
        if (stripped.length() <= maxLength) {
            return stripped;
        }
        return stripped.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String newMemoryId() {
        return "mem-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
