package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 沙箱执行规格响应（值对象协议层表示）
 */
public record SandboxSpecResponse(
        String image,
        int memoryMb,
        double cpu,
        String workspaceMode,
        int timeoutSeconds
) {
}