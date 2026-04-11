package com.linkroa.deepdataagent.agent.acl.sandbox;

public interface AgentSandboxGateway {

    SandboxSession openSession(String requestedImage);

    SandboxExecutionResult execute(String sandboxId, SandboxExecutionRequest request);

    void closeSession(String sandboxId);
}
