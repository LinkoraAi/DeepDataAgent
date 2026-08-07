package com.linkroa.deepdataagent.agent.domain.service.port;

/**
 * LLM 生成端口
 * <p>领域层端口接口，用于抽象 LLM 能力调用（图表配置、会话标题），
 * 由基础设施层实现，实现领域层与具体 LLM 提供商的解耦。</p>
 */
public interface LLMGenerationPort {

    /**
     * 生成 ECharts 图表配置
     *
     * @param modelConfigId  模型配置 ID
     * @param dataDescription 数据结构描述
     * @param userQuestion   用户问题
     * @param sessionId      会话 ID（用于流式回调，可为 null）
     * @return ECharts 配置 JSON 字符串
     */
    String generateChartConfig(Long modelConfigId, String dataDescription, String userQuestion, String sessionId);

    /**
     * 生成会话标题
     *
     * @param modelConfigId 模型配置 ID
     * @param userQuestion  用户问题
     * @return 生成的标题，失败时返回 null
     */
    String generateTitle(Long modelConfigId, String userQuestion);
}