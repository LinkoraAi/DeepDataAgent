package com.linkroa.deepdataagent.agent.infrastructure.sandbox.opensandbox.adapter;

import com.linkroa.deepdataagent.agent.acl.sandbox.AgentSandboxGateway;
import com.linkroa.deepdataagent.agent.acl.sandbox.SandboxExecutionRequest;
import com.linkroa.deepdataagent.agent.acl.sandbox.SandboxExecutionResult;
import com.linkroa.deepdataagent.agent.acl.sandbox.SandboxSession;
import com.linkroa.deepdataagent.shared.config.OpenSandboxProperties;
import org.springframework.stereotype.Component;

@Component
public class OpenSandboxGatewayAdapter implements AgentSandboxGateway {

    private final OpenSandboxProperties openSandboxProperties;

    public OpenSandboxGatewayAdapter(OpenSandboxProperties openSandboxProperties) {
        this.openSandboxProperties = openSandboxProperties;
    }

    @Override
    public SandboxSession openSession(String requestedImage) {
        String image = requestedImage == null || requestedImage.isBlank()
                ? openSandboxProperties.getDefaultImage()
                : requestedImage;
        return new SandboxSession("reserved-session", image, openSandboxProperties.isUseServerProxy());
    }

    @Override
    public SandboxExecutionResult execute(String sandboxId, SandboxExecutionRequest request) {
        return new SandboxExecutionResult(
                sandboxId,
                "PENDING",
                "OpenSandbox command execution will be wired in a later iteration.",
                request.command()
        );
    }

    @Override
    public void closeSession(String sandboxId) {
        // Lifecycle orchestration is intentionally deferred while the bounded contexts are being initialized.
    }
}
