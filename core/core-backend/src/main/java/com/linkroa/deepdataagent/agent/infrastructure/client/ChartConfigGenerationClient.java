package com.linkroa.deepdataagent.agent.infrastructure.client;

import org.springframework.stereotype.Component;

/**
 * 图表配置生成客户端
 * <p>根据数据结构与用户意图生成 ECharts 配置 JSON。
 * 提示词约束输出格式与图表类型选择（时间趋势 line / 分类对比 bar / 占比 pie），
 * 具体调用由 {@link LLMInvoker} 执行。</p>
 */
@Component
public class ChartConfigGenerationClient {

    private final LLMInvoker llmInvoker;

    /**
     * 构造方法
     *
     * @param llmInvoker LLM 通用调用器
     */
    public ChartConfigGenerationClient(LLMInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    /**
     * 生成图表配置（ECharts JSON，无流式回调）
     *
     * @param modelConfigId   模型配置 ID
     * @param dataDescription 数据结构描述
     * @param text            用户问题
     * @return ECharts 配置 JSON 字符串
     */
    public String generateChartConfig(Long modelConfigId, String dataDescription, String text) {
        return generateChartConfig(modelConfigId, dataDescription, text, null);
    }

    /**
     * 生成图表配置（ECharts JSON，带 sessionId 用于流式回调）
     *
     * @param modelConfigId   模型配置 ID
     * @param dataDescription 数据结构描述
     * @param text            用户问题
     * @param sessionId       会话 ID（用于流式回调，可为 null）
     * @return ECharts 配置 JSON 字符串
     */
    public String generateChartConfig(Long modelConfigId, String dataDescription, String text, String sessionId) {
        String systemPrompt = """
                你是一个数据可视化专家。根据数据结构和用户意图，生成 ECharts 配置 JSON。
                要求：
                1. 只输出 JSON，不要添加其他内容或 markdown 代码块标记
                2. 根据数据类型自动选择最合适的图表（时间趋势用 line，分类对比用 bar，占比用 pie）
                3. JSON 格式必须符合 ECharts 规范
                """;

        String userPrompt = """
                数据结构：%s
                用户意图：%s
                请生成 ECharts 配置 JSON：
                """.formatted(dataDescription, text);

        return llmInvoker.invoke(modelConfigId, systemPrompt, userPrompt, sessionId);
    }
}