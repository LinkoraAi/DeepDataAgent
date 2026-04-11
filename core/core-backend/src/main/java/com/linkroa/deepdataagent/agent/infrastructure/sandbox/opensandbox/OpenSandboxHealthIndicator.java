package com.linkroa.deepdataagent.agent.infrastructure.sandbox.opensandbox;

import com.linkroa.deepdataagent.agent.infrastructure.sandbox.opensandbox.client.OpenSandboxRestClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class OpenSandboxHealthIndicator implements HealthIndicator {

    private final OpenSandboxRestClient openSandboxRestClient;

    public OpenSandboxHealthIndicator(OpenSandboxRestClient openSandboxRestClient) {
        this.openSandboxRestClient = openSandboxRestClient;
    }

    @Override
    public Health health() {
        if (openSandboxRestClient.isHealthy()) {
            return Health.up().withDetail("provider", "OpenSandbox").build();
        }
        return Health.down().withDetail("provider", "OpenSandbox").build();
    }
}
