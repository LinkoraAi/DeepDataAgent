package com.linkroa.deepdataagent.shared.config;

import com.linkroa.deepdataagent.shared.result.ErrorEnvelope;
import com.linkroa.deepdataagent.shared.result.ErrorType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * JSON 请求体大小上限过滤器（shared/api-conventions：JSON 请求体上限 MUST 为 4MB）。
 * <p>超限请求对齐网关截断语义——按「JSON 不可解析」处理，返回
 * {@code 400 invalid_request_error}（Request body must be valid JSON.）统一错误信封；
 * multipart 文件上传走独立限额（单文件 50MB，见 runtime/files），不在本过滤器范围。
 * 拦截分两条路径：{@code Content-Length} 明确超限时直接快速拒绝；
 * 其余 JSON 请求（含分块传输 chunked，Content-Length 为 -1）以包装 ServletInputStream
 * 的方式边读边计数，累计超过上限即抛 {@link IOException}，由 Spring 收敛为
 * {@code HttpMessageNotReadableException} 并经 GlobalExceptionHandler 映射为 400。</p>
 */
@Component
public class JsonBodySizeLimitFilter extends OncePerRequestFilter {

    /** JSON 请求体上限（4MB）。 */
    public static final long MAX_JSON_BODY_BYTES = 4L * 1024 * 1024;

    /** 错误信封序列化器（与 GlobalExceptionHandler / JwtAuthenticationFilter 形状一致）。 */
    private static final ObjectMapper ERROR_ENVELOPE_MAPPER = JsonMapper.builder().build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            // 快速拒绝：Content-Length 已明确超限
            if (request.getContentLengthLong() > MAX_JSON_BODY_BYTES) {
                reject(response);
                return;
            }
            // 包装限额：chunked（Content-Length = -1）或长度可信但需流内兜底的 JSON 请求
            filterChain.doFilter(new BodySizeLimitingRequest(request), response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        // 统一错误信封（与 GlobalExceptionHandler / JwtAuthenticationFilter 形状一致）
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ERROR_ENVELOPE_MAPPER.writeValueAsString(
                ErrorEnvelope.of(ErrorType.INVALID_REQUEST_ERROR, "Request body must be valid JSON.")));
    }

    /**
     * JSON 请求包装器：以计数版 ServletInputStream 限额读取请求体，
     * 累计读满 {@link #MAX_JSON_BODY_BYTES} 之后的字节即抛 IOException（截断语义同超限拒绝）。
     */
    private static final class BodySizeLimitingRequest extends HttpServletRequestWrapper {

        private BodySizeLimitingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CountingServletInputStream(super.getInputStream(), MAX_JSON_BODY_BYTES);
        }
    }

    /** 读取字节数限额的输入流包装（超限时在读取点抛出 IOException）。 */
    private static final class CountingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long consumed;

        private CountingServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1 && ++consumed > limit) {
                throw new IOException("JSON request body exceeds " + limit + " bytes limit.");
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0 && (consumed += read) > limit) {
                throw new IOException("JSON request body exceeds " + limit + " bytes limit.");
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            // Servlet 6.1 起 isFinished() 不再声明 IOException，超限判定优先短路
            return consumed > limit || delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
