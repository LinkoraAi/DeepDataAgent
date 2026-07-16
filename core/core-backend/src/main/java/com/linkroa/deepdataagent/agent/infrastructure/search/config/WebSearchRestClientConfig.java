package com.linkroa.deepdataagent.agent.infrastructure.search.config;

import com.linkroa.deepdataagent.agent.infrastructure.config.WebSearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 网络搜索 RestClient 配置
 * <p>为 TavilyWebSearchService 提供 RestClient Bean，便于测试时 Mock。</p>
 * <p>Bean 始终创建，条件化注册到 Agent 的逻辑在 DataAnalysisApplicationService.buildAgent() 中实现。</p>
 */
@Configuration
@EnableConfigurationProperties(WebSearchProperties.class)
public class WebSearchRestClientConfig {

    /**
     * 创建用于网络搜索的 RestClient
     * <p>不设置 baseUrl，因为 endpoint 由 WebSearchProperties 动态配置。
     * 设置连接超时和读取超时，防止请求长时间阻塞。</p>
     *
     * @param properties 网络搜索配置属性
     * @return RestClient 实例
     */
    @Bean
    public RestClient webSearchRestClient(WebSearchProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
