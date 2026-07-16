package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.agent.domain.model.SearchResult;
import com.linkroa.deepdataagent.agent.domain.service.WebSearchService;
import com.linkroa.deepdataagent.agent.infrastructure.config.WebSearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WebSearchTool 单元测试
 */
@ExtendWith(MockitoExtension.class)
class WebSearchToolTest {

    @Mock
    private WebSearchService webSearchService;

    @Mock
    private WebSearchProperties properties;

    private ObjectMapper objectMapper;

    private WebSearchTool webSearchTool;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getMaxResults()).thenReturn(5);
        objectMapper = new ObjectMapper();
        webSearchTool = new WebSearchTool(webSearchService, properties, objectMapper);
    }

    @Test
    void should_returnFormattedResults_when_search_given_validQuery() {
        // given
        String query = "test query";
        Integer maxResults = 5;
        List<SearchResult> mockResults = List.of(
                SearchResult.of("Title 1", "https://example.com/1", "Snippet 1", "Content 1"),
                SearchResult.of("Title 2", "https://example.com/2", "Snippet 2", "Content 2")
        );
        when(webSearchService.search(query, maxResults)).thenReturn(mockResults);

        // when
        String result = webSearchTool.search(query, maxResults);

        // then
        assertThat(result).contains("Title 1");
        assertThat(result).contains("https://example.com/1");
        assertThat(result).contains("Snippet 1");
        assertThat(result).contains("Title 2");
        assertThat(result).contains("https://example.com/2");
        assertThat(result).contains("Snippet 2");
        verify(webSearchService, times(1)).search(query, maxResults);
    }

    @Test
    void should_returnEmptyMessage_when_search_given_emptyQuery() {
        // given
        String query = "";
        Integer maxResults = 5;

        // when
        String result = webSearchTool.search(query, maxResults);

        // then
        assertThat(result).isEqualTo("搜索查询为空，无法执行搜索。");
        verify(webSearchService, never()).search(anyString(), anyInt());
    }

    @Test
    void should_returnEmptyMessage_when_search_given_nullQuery() {
        // given
        String query = null;
        Integer maxResults = 5;

        // when
        String result = webSearchTool.search(query, maxResults);

        // then
        assertThat(result).isEqualTo("搜索查询为空，无法执行搜索。");
        verify(webSearchService, never()).search(anyString(), anyInt());
    }

    @Test
    void should_truncateQuery_when_search_given_queryExceedsMaxLength() {
        // given
        String longQuery = "a".repeat(250);
        Integer maxResults = 5;
        List<SearchResult> mockResults = List.of();
        when(webSearchService.search(anyString(), eq(maxResults))).thenReturn(mockResults);

        // when
        webSearchTool.search(longQuery, maxResults);

        // then
        String expectedQuery = "a".repeat(200);
        verify(webSearchService, times(1)).search(expectedQuery, maxResults);
    }

    @Test
    void should_returnErrorMessage_when_search_given_serviceThrowsException() {
        // given
        String query = "test query";
        Integer maxResults = 5;
        when(webSearchService.search(query, maxResults)).thenThrow(new RuntimeException("API error"));

        // when
        String result = webSearchTool.search(query, maxResults);

        // then
        assertThat(result).isEqualTo("搜索失败: API error");
        verify(webSearchService, times(1)).search(query, maxResults);
    }

    @Test
    void should_useDefaultMaxResults_when_search_given_nullMaxResults() {
        // given
        String query = "test query";
        Integer maxResults = null;
        List<SearchResult> mockResults = List.of();
        when(webSearchService.search(query, 5)).thenReturn(mockResults);

        // when
        webSearchTool.search(query, maxResults);

        // then
        verify(webSearchService, times(1)).search(query, 5);
    }

    @Test
    void should_useConfiguredDefaultMaxResults_when_search_given_nullMaxResults() {
        // given
        when(properties.getMaxResults()).thenReturn(10);
        String query = "test query";
        Integer maxResults = null;
        List<SearchResult> mockResults = List.of();
        when(webSearchService.search(query, 10)).thenReturn(mockResults);

        // when
        webSearchTool.search(query, maxResults);

        // then
        verify(webSearchService, times(1)).search(query, 10);
    }

    @Test
    void should_useProvidedMaxResults_when_search_given_positiveMaxResults() {
        // given
        String query = "test query";
        Integer maxResults = 10;
        List<SearchResult> mockResults = List.of();
        when(webSearchService.search(query, maxResults)).thenReturn(mockResults);

        // when
        webSearchTool.search(query, maxResults);

        // then
        verify(webSearchService, times(1)).search(query, maxResults);
    }

    @Test
    void should_useDefaultMaxResults_when_search_given_zeroMaxResults() {
        // given
        String query = "test query";
        Integer maxResults = 0;
        List<SearchResult> mockResults = List.of();
        when(webSearchService.search(query, 5)).thenReturn(mockResults);

        // when
        webSearchTool.search(query, maxResults);

        // then
        verify(webSearchService, times(1)).search(query, 5);
    }

    @Test
    void should_useDefaultMaxResults_when_search_given_negativeMaxResults() {
        // given
        String query = "test query";
        Integer maxResults = -1;
        List<SearchResult> mockResults = List.of();
        when(webSearchService.search(query, 5)).thenReturn(mockResults);

        // when
        webSearchTool.search(query, maxResults);

        // then
        verify(webSearchService, times(1)).search(query, 5);
    }

    @Test
    void should_returnEmptyResultsMessage_when_search_given_noResultsFound() {
        // given
        String query = "test query";
        Integer maxResults = 5;
        List<SearchResult> emptyResults = List.of();
        when(webSearchService.search(query, maxResults)).thenReturn(emptyResults);

        // when
        String result = webSearchTool.search(query, maxResults);

        // then
        assertThat(result).isEqualTo("未找到相关搜索结果。");
        verify(webSearchService, times(1)).search(query, maxResults);
    }

    @Test
    void should_returnEmptyMessage_when_search_given_whitespaceOnlyQuery() {
        // given
        String query = "   ";
        Integer maxResults = 5;

        // when
        String result = webSearchTool.search(query, maxResults);

        // then
        assertThat(result).isEqualTo("搜索查询为空，无法执行搜索。");
        verify(webSearchService, never()).search(anyString(), anyInt());
    }

    @Test
    void should_returnUnknownError_when_search_given_exceptionWithNullMessage() {
        // given
        String query = "test query";
        Integer maxResults = 5;
        when(webSearchService.search(query, maxResults)).thenThrow(new RuntimeException((String) null));

        // when
        String result = webSearchTool.search(query, maxResults);

        // then
        assertThat(result).isEqualTo("搜索失败: 未知错误");
        verify(webSearchService, times(1)).search(query, maxResults);
    }
}
