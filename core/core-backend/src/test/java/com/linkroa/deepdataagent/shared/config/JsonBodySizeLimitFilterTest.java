package com.linkroa.deepdataagent.shared.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JsonBodySizeLimitFilter} JSON 请求体 4MB 上限单测（6.1 请求限额）。
 * <p>直接驱动 {@code doFilterInternal}（绕开 OncePerRequestFilter 的派发簿记）；
 * Spring 7 的 MockHttpServletRequest 长度取自 content 字节数组、无法模拟超大
 * Content-Length，故请求用 Mockito 打桩；chunked（Content-Length = -1）场景
 * 经打桩流驱动包装计数路径。</p>
 */
class JsonBodySizeLimitFilterTest {

    private final JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter();

    @Test
    void should_rejectWithInvalidRequestEnvelope_when_doFilter_given_jsonBodyOver4Mb()
            throws ServletException, IOException {
        // given（Content-Length 超 4MB 的 JSON 请求）
        HttpServletRequest request = mockRequest("application/json", JsonBodySizeLimitFilter.MAX_JSON_BODY_BYTES + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilterInternal(request, response, chain);

        // then（400 + 统一错误信封，不进入下游）
        assertNull(chain.getRequest(), "超限请求不得进入下游过滤器链");
        assertEquals(400, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"invalid_request_error\""));
        assertTrue(body.contains("Request body must be valid JSON."));
        assertTrue(body.contains("\"request_id\""));
    }

    @Test
    void should_passThroughWrapped_when_doFilter_given_jsonBodyWithinLimit() throws ServletException, IOException {
        // given
        HttpServletRequest request = mockRequest("application/json;charset=UTF-8", 1024L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilterInternal(request, response, chain);

        // then（JSON 请求经限额包装后透传，原始请求保持可读）
        HttpServletRequest passed = assertInstanceOf(HttpServletRequestWrapper.class, chain.getRequest());
        assertEquals(request, ((HttpServletRequestWrapper) passed).getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void should_passThroughUnwrapped_when_doFilter_given_multipartUpload() throws ServletException, IOException {
        // given（multipart 文件上传走独立限额，不受 JSON 上限约束、不包装）
        HttpServletRequest request = mockRequest("multipart/form-data", 50L * 1024 * 1024);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilterInternal(request, response, chain);

        // then
        assertEquals(request, chain.getRequest());
    }

    @Test
    void should_throwIoError_when_doFilter_given_chunkedJsonBodyOverLimitInStream()
            throws ServletException, IOException {
        // given（chunked：Content-Length = -1，流内实际字节超 4MB，快速拒绝路径失效）
        byte[] oversized = new byte[(int) JsonBodySizeLimitFilter.MAX_JSON_BODY_BYTES + 16];
        HttpServletRequest request = mockRequest("application/json", -1L);
        when(request.getInputStream()).thenReturn(servletStream(oversized));
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        // then（下游拿到包装请求，读取超限字节时抛 IOException → Spring 收敛为 400）
        HttpServletRequest passed = assertInstanceOf(HttpServletRequestWrapper.class, chain.getRequest());
        assertThrows(IOException.class, () -> passed.getInputStream().readAllBytes());
    }

    @Test
    void should_readWholeBody_when_doFilter_given_chunkedJsonBodyWithinLimit()
            throws ServletException, IOException {
        // given（chunked 且体量在限内：包装流须完整透传内容）
        byte[] body = "{\"q\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request = mockRequest("application/json", -1L);
        when(request.getInputStream()).thenReturn(servletStream(body));
        MockFilterChain chain = new MockFilterChain();

        // when
        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        // then（内容可读全且不触发限额）
        HttpServletRequest passed = assertInstanceOf(HttpServletRequestWrapper.class, chain.getRequest());
        assertArrayEquals(body, passed.getInputStream().readAllBytes());
    }

    /** 构造带 Content-Type 与 Content-Length 的打桩请求。 */
    private static HttpServletRequest mockRequest(String contentType, long contentLength) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn(contentType);
        when(request.getContentLengthLong()).thenReturn(contentLength);
        return request;
    }

    /** 以字节数组驱动的最小 ServletInputStream（模拟 chunked 请求体流）。 */
    private static ServletInputStream servletStream(byte[] bytes) {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // 同步读取测试桩，无需异步监听
            }
        };
    }
}
