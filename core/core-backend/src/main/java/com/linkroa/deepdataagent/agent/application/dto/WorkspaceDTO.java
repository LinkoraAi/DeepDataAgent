package com.linkroa.deepdataagent.agent.application.dto;

import java.util.List;

/**
 * 工作区信息 DTO
 * <p>应用层返回的工作区信息对象，由控制器层转换为 {@link com.linkroa.deepdataagent.agent.controller.response.AgentWorkspaceResponse}。</p>
 *
 * @param applicationName    应用名称
 * @param boundedContexts    限界上下文列表
 * @param sandboxEnabled     沙箱是否启用
 * @param serverProxyEnabled 是否使用服务器代理
 * @param sqlitePath         SQLite 数据库路径
 */
public record WorkspaceDTO(
        String applicationName,
        List<String> boundedContexts,
        boolean sandboxEnabled,
        boolean serverProxyEnabled,
        String sqlitePath
) {
}