package com.linkroa.deepdataagent.agent.domain.model;

/**
 * 连接测试结果值对象
 * <p>表示一次模型连接测试的结果，被基础设施层、应用层与控制器层共同使用。</p>
 *
 * @param available    是否可用
 * @param message      测试结果消息
 * @param responseTime 响应耗时（毫秒）
 */
public record TestConnectionResult(
    Boolean available,
    String message,
    Long responseTime
) {}