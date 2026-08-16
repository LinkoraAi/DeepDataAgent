package com.linkroa.deepdataagent.agent.controller.response;

/**
 * Agent 详情响应 DTO（定义信息 + 最新版本快照）
 */
public record AgentDetailResponse(
        AgentResponse agent,
        AgentVersionResponse latestVersion
) {
}