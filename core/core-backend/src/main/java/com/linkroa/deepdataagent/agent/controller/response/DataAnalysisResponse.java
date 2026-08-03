package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 数据分析响应
 * <p>数据分析接口的响应体，包含会话 ID 和分析状态消息。</p>
 *
 * @param sessionId 会话 ID
 * @param message   分析状态消息
 */
public record DataAnalysisResponse(String sessionId, String message) {
}