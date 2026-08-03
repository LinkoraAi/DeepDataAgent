package com.linkroa.deepdataagent.agent.infrastructure.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.model.SearchResult;
import com.linkroa.deepdataagent.agent.infrastructure.config.WebSearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/**
 * TavilyWebSearchService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class TavilyWebSearchServiceTest {

    @Mock
    private WebSearchProperties properties;

    @Mock
    private RestClient restClient;

    /**
     * 使用真实 ObjectMapper，避免 Mock JsonNode 链式调用的复杂性
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TavilyWebSearchService service;

    @BeforeEach
    void setUp() {
        service = new TavilyWebSearchService(properties, restClient, objectMapper);
    }

    /**
     * 构建 RestClient Mock 链，返回指定的响应体
     * <p>链式调用：restClient.post() -> uriSpec.uri() -> uriSpec.body() -> bodySpec.retrieve() -> responseSpec.body()</p>
     */
    private void mockRestClientResponse(String responseBody) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        when(uriSpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(responseBody);
    }

    @Test
    void should_returnSearchResults_when_search_given_apiReturnsValidResponse() {
        // given
        String query = "test query";
        int maxResults = 5;
        String mockResponse = "{\"results\": [{\"title\": \"Title 1\", \"url\": \"https://example.com/1\", \"content\": \"Content 1\"}]}";

        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        mockRestClientResponse(mockResponse);

        // when
        List<SearchResult> results = service.search(query, maxResults);

        // then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Title 1");
        assertThat(results.get(0).url()).isEqualTo("https://example.com/1");
        assertThat(results.get(0).snippet()).isEqualTo("Content 1");
    }

    @Test
    void should_returnEmptyList_when_search_given_apiKeyIsEmpty() {
        // given
        when(properties.getApiKey()).thenReturn("");

        // when
        List<SearchResult> results = service.search("test", 5);

        // then
        assertThat(results).isEmpty();
        verify(restClient, never()).post();
    }

    @Test
    void should_returnEmptyList_when_search_given_apiKeyIsNull() {
        // given
        when(properties.getApiKey()).thenReturn(null);

        // when
        List<SearchResult> results = service.search("test", 5);

        // then
        assertThat(results).isEmpty();
        verify(restClient, never()).post();
    }

    @Test
    void should_returnEmptyList_when_search_given_apiCallThrowsException() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        when(uriSpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("API error"));

        // when
        List<SearchResult> results = service.search("test", 5);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    void should_returnEmptyList_when_search_given_responseParsingFails() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");

        // 返回一个非 JSON 字符串，objectMapper.readTree 会抛异常
        mockRestClientResponse("not valid json at all {{{");

        // when
        List<SearchResult> results = service.search("test", 5);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    void should_returnEmptyList_when_search_given_resultsNodeIsMissing() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");

        // 返回没有 results 字段的 JSON
        mockRestClientResponse("{}");

        // when
        List<SearchResult> results = service.search("test", 5);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    void should_returnEmptyList_when_search_given_resultsNodeIsNotArray() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");

        // results 字段不是数组
        mockRestClientResponse("{\"results\": \"not an array\"}");

        // when
        List<SearchResult> results = service.search("test", 5);

        // then
        assertThat(results).isEmpty();
    }

    @Test
    void should_truncateSnippet_when_search_given_contentExceedsMaxLength() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");

        String longContent = "a".repeat(600);
        String mockResponse = "{\"results\": [{\"title\": \"Title\", \"url\": \"https://example.com\", \"content\": \"" + longContent + "\"}]}";
        mockRestClientResponse(mockResponse);

        // when
        List<SearchResult> results = service.search("test", 5);

        // then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).snippet()).hasSize(503); // 500 + "..."
        assertThat(results.get(0).snippet()).endsWith("...");
        // 原始内容保持完整
        assertThat(results.get(0).content()).hasSize(600);
    }

    @Test
    void should_returnEmptyList_when_search_given_resultsNodeIsEmptyArray() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        
        // 返回空数组
        mockRestClientResponse("{\"results\": []}");
        
        // when
        List<SearchResult> results = service.search("test", 5);
        
        // then
        assertThat(results).isEmpty();
    }

    @Test
    void should_returnMultipleResults_when_search_given_apiReturnsMultipleResults() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        
        String mockResponse = "{\"results\": [" +
                "{\"title\": \"Title 1\", \"url\": \"https://example.com/1\", \"content\": \"Content 1\"}," +
                "{\"title\": \"Title 2\", \"url\": \"https://example.com/2\", \"content\": \"Content 2\"}," +
                "{\"title\": \"Title 3\", \"url\": \"https://example.com/3\", \"content\": \"Content 3\"}" +
                "]}";
        mockRestClientResponse(mockResponse);
        
        // when
        List<SearchResult> results = service.search("test", 5);
        
        // then
        assertThat(results).hasSize(3);
        assertThat(results.get(0).title()).isEqualTo("Title 1");
        assertThat(results.get(1).title()).isEqualTo("Title 2");
        assertThat(results.get(2).title()).isEqualTo("Title 3");
    }

    @Test
    void should_useCorrectEndpoint_when_search_given_validRequest() {
        // given
        String expectedEndpoint = "https://api.tavily.com/search";
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn(expectedEndpoint);
        
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(expectedEndpoint)).thenReturn(uriSpec);
        when(uriSpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("{\"results\": []}");
        
        // when
        service.search("test", 5);
        
        // then
        verify(uriSpec).uri(expectedEndpoint);
    }

    @Test
    void should_buildCorrectRequestBody_when_search_given_validRequest() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        when(uriSpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("{\"results\": []}");
        
        // when
        service.search("test query", 10);
        
        // then
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(uriSpec).body(bodyCaptor.capture());
        
        Object capturedBody = bodyCaptor.getValue();
        assertThat(capturedBody).isInstanceOf(java.util.Map.class);
        
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> bodyMap = (java.util.Map<String, Object>) capturedBody;
        assertThat(bodyMap.get("api_key")).isEqualTo("test-api-key");
        assertThat(bodyMap.get("query")).isEqualTo("test query");
        assertThat(bodyMap.get("max_results")).isEqualTo(10);
    }

    @Test
    void should_handleNullContent_when_search_given_resultWithNullContent() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        
        // content 字段为 null
        String mockResponse = "{\"results\": [{\"title\": \"Title\", \"url\": \"https://example.com\", \"content\": null}]}";
        mockRestClientResponse(mockResponse);
        
        // when
        List<SearchResult> results = service.search("test", 5);
        
        // then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEqualTo("Title");
        assertThat(results.get(0).url()).isEqualTo("https://example.com");
        assertThat(results.get(0).snippet()).isEmpty();
        assertThat(results.get(0).content()).isEmpty();
    }

    @Test
    void should_handleMissingFields_when_search_given_resultWithMissingFields() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        
        // 缺少 title 和 url 字段
        String mockResponse = "{\"results\": [{\"content\": \"Content only\"}]}";
        mockRestClientResponse(mockResponse);
        
        // when
        List<SearchResult> results = service.search("test", 5);
        
        // then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).title()).isEmpty();
        assertThat(results.get(0).url()).isEmpty();
        assertThat(results.get(0).snippet()).isEqualTo("Content only");
        assertThat(results.get(0).content()).isEqualTo("Content only");
    }

    @Test
    void should_returnEmptyList_when_search_given_emptyStringResponse() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        
        // 返回空字符串
        mockRestClientResponse("");
        
        // when
        List<SearchResult> results = service.search("test", 5);
        
        // then
        assertThat(results).isEmpty();
    }

    @Test
    void should_returnEmptyList_when_search_given_nullResponse() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        
        // 返回 null
        mockRestClientResponse(null);
        
        // when
        List<SearchResult> results = service.search("test", 5);
        
        // then
        assertThat(results).isEmpty();
    }

    @Test
    void should_returnEmptyList_when_search_given_readTreeReturnsNull() {
        // given: ObjectMapper.readTree 返回 null（使用 mock ObjectMapper）
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        TavilyWebSearchService serviceWithMockMapper = new TavilyWebSearchService(properties, restClient, mockMapper);

        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        when(uriSpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("{}");

        try {
            when(mockMapper.readTree(anyString())).thenReturn(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // when
        List<SearchResult> results = serviceWithMockMapper.search("test", 5);

        // then: root == null 分支被覆盖，返回空列表
        assertThat(results).isEmpty();
    }

    @Test
    void should_skipNullNodes_when_search_given_resultsArrayContainsNull() {
        // given
        lenient().when(properties.getApiKey()).thenReturn("test-api-key");
        lenient().when(properties.getEndpoint()).thenReturn("https://api.tavily.com/search");
        
        // results 数组中包含 null 节点
        String mockResponse = "{\"results\": [{\"title\": \"Title 1\", \"url\": \"https://example.com/1\", \"content\": \"Content 1\"}, null, {\"title\": \"Title 2\", \"url\": \"https://example.com/2\", \"content\": \"Content 2\"}]}";
        mockRestClientResponse(mockResponse);
        
        // when
        List<SearchResult> results = service.search("test", 5);
        
        // then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("Title 1");
        assertThat(results.get(1).title()).isEqualTo("Title 2");
    }
}
