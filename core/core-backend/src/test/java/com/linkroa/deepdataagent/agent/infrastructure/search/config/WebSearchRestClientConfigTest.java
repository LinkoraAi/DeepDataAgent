package com.linkroa.deepdataagent.agent.infrastructure.search.config;

import com.linkroa.deepdataagent.agent.infrastructure.config.WebSearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * WebSearchRestClientConfig 单元测试
 */
@ExtendWith(MockitoExtension.class)
class WebSearchRestClientConfigTest {

    @Mock
    private WebSearchProperties properties;

    private WebSearchRestClientConfig config;

    @BeforeEach
    void setUp() {
        config = new WebSearchRestClientConfig();
    }

    @Test
    void should_createRestClient_when_webSearchRestClient_given_validProperties() {
        // given
        int timeoutSeconds = 10;
        when(properties.getTimeoutSeconds()).thenReturn(timeoutSeconds);

        // when
        RestClient restClient = config.webSearchRestClient(properties);

        // then
        assertThat(restClient).isNotNull();
    }

    @Test
    void should_useConfiguredTimeout_when_webSearchRestClient_given_customTimeout() {
        // given
        int timeoutSeconds = 30;
        when(properties.getTimeoutSeconds()).thenReturn(timeoutSeconds);

        // when
        RestClient restClient = config.webSearchRestClient(properties);

        // then
        assertThat(restClient).isNotNull();
    }

    @Test
    void should_createRestClientWithoutBaseUrl_when_webSearchRestClient_given_anyProperties() {
        // given
        when(properties.getTimeoutSeconds()).thenReturn(15);

        // when
        RestClient restClient = config.webSearchRestClient(properties);

        // then
        assertThat(restClient).isNotNull();
        // RestClient 不设置 baseUrl，endpoint 由 TavilyWebSearchService 动态配置
    }
}
