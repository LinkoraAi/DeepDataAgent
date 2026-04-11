package com.linkroa.deepdataagent.agent.controller.response;

import java.util.List;

public record AgentWorkspaceResponse(
        String applicationName,
        List<String> boundedContexts,
        boolean sandboxEnabled,
        boolean serverProxyEnabled,
        String sqlitePath
) {
}
