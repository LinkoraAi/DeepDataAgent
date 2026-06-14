package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 连接测试结果响应
 */
public record TestConnectionResult(
    Boolean available,
    String message,
    Long responseTime
) {}
