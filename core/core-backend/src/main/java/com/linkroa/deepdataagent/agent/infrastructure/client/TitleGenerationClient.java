package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.datasource.infrastructure.util.LogMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话标题生成客户端
 * <p>调用 LLM 根据用户问题生成简洁的中文会话标题（不超过 15 字）。
 * 生成失败时返回 null，由调用方使用降级标题，不影响主流程。</p>
 */
@Component
public class TitleGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(TitleGenerationClient.class);

    private final LLMInvoker llmInvoker;

    /**
     * 构造方法
     *
     * @param llmInvoker LLM 通用调用器
     */
    public TitleGenerationClient(LLMInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    /**
     * 生成会话标题
     *
     * @param modelConfigId 模型配置 ID
     * @param text          用户问题
     * @return 生成的标题，失败时返回 null
     */
    public String generateTitle(Long modelConfigId, String text) {
        String systemPrompt = """
                你是一个标题生成专家。根据用户的问题，生成一个简洁的会话标题。
                要求：
                1. 标题不超过 15 个字
                2. 使用中文
                3. 只输出标题，不要添加任何解释或标记
                """;

        String userPrompt = "用户问题：%s\n请生成会话标题：".formatted(text);

        try {
            return llmInvoker.invoke(modelConfigId, systemPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("生成标题失败，将使用降级标题: {}", LogMasker.mask(e.getMessage()));
            return null;
        }
    }
}