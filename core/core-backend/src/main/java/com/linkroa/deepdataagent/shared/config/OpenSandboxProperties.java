package com.linkroa.deepdataagent.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.opensandbox")
public class OpenSandboxProperties {

    private String protocol = "http";
    private String domain = "opensandbox:8080";
    private String apiKey = "deepdataagent-internal-key";
    private boolean useServerProxy = true;
    private String defaultImage = "opensandbox/code-interpreter:latest";
    private int defaultTimeoutSeconds = 1800;

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isUseServerProxy() {
        return useServerProxy;
    }

    public void setUseServerProxy(boolean useServerProxy) {
        this.useServerProxy = useServerProxy;
    }

    public String getDefaultImage() {
        return defaultImage;
    }

    public void setDefaultImage(String defaultImage) {
        this.defaultImage = defaultImage;
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public String getBaseUrl() {
        return protocol + "://" + domain;
    }
}
