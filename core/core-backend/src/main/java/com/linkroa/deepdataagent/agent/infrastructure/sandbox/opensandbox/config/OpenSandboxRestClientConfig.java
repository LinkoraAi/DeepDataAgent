package com.linkroa.deepdataagent.agent.infrastructure.sandbox.opensandbox.config;

import com.linkroa.deepdataagent.shared.config.OpenSandboxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenSandboxRestClientConfig {

    @Bean
    public RestClient openSandboxRestTransport(OpenSandboxProperties properties) {
        return RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }
}
