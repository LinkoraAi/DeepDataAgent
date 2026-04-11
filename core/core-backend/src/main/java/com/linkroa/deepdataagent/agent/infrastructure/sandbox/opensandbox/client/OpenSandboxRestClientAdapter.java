package com.linkroa.deepdataagent.agent.infrastructure.sandbox.opensandbox.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OpenSandboxRestClientAdapter implements OpenSandboxRestClient {

    private final RestClient restClient;

    public OpenSandboxRestClientAdapter(RestClient openSandboxRestTransport) {
        this.restClient = openSandboxRestTransport;
    }

    @Override
    public boolean isHealthy() {
        try {
            HttpStatusCode status = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return status.is2xxSuccessful();
        } catch (RestClientException ex) {
            return false;
        }
    }
}
