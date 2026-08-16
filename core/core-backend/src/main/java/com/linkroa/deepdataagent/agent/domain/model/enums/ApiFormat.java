package com.linkroa.deepdataagent.agent.domain.model.enums;

/**
 * API 格式枚举：界定模型提供方接口规范与运行时装配的模型标识前缀
 */
public enum ApiFormat {

    /** AgentScope 内置模型（dashscope:qwen-plus 等） */
    AGENTSCOPE,
    /** OpenAI 兼容格式 */
    OPENAI,
    /** 百炼（Bailian）平台 */
    BAILIAN,
    /** 其他兼容格式 */
    OTHER
}