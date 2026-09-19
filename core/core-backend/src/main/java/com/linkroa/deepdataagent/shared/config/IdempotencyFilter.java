package com.linkroa.deepdataagent.shared.config;

import com.linkroa.deepdataagent.shared.idempotency.IdempotencyRecord;
import com.linkroa.deepdataagent.shared.idempotency.IdempotencyRecordStore;
import com.linkroa.deepdataagent.shared.result.ErrorEnvelope;
import com.linkroa.deepdataagent.shared.result.ErrorType;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 幂等过滤器（{@code Idempotency-Key} 请求头，键按用户隔离，仅保护 {@code POST /agents}）。
 *
 * <p>行为契约：携带同一键（同归属用户）的重复提交返回首次创建结果（原样回放状态码与响应体），
 * 不产生第二个 Agent；同键但请求体不同一律 {@code 409 conflict_error}；无键按普通创建处理。</p>
 *
 * <p>实现要点：请求体先在过滤器内一次性读入并计算 SHA-256，再以可重读包装下传（下游
 * {@code JsonBodySizeLimitFilter} / 消息转换器照常读取）；响应经
 * {@link ContentCachingResponseWrapper} 缓冲，仅在 2xx 时登记记录（失败不缓存，允许重试）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdempotencyFilter extends OncePerRequestFilter {

    /** 幂等键请求头名。 */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** 作用域名（仅 POST /agents）。 */
    public static final String SCOPE_POST_AGENTS = "post_agents";

    /** 受保护端点路径后缀（对本 PR 的 {@code /agent/agents} 与后续 {@code /cloud/agents} 同源匹配）。 */
    private static final String PROTECTED_PATH_SUFFIX = "/agents";

    /** 请求体读取上限（与 JSON 限额一致；超限交由大小限额过滤器拒绝，不在本过滤器处理）。 */
    private static final long MAX_BODY_BYTES = 4L * 1024 * 1024;

    /** 错误信封序列化器（与 GlobalExceptionHandler / JsonBodySizeLimitFilter 形状一致）。 */
    private static final ObjectMapper ERROR_ENVELOPE_MAPPER = JsonMapper.builder().build();

    @Resource
    private IdempotencyRecordStore idempotencyRecordStore;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isProtected(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        Long ownerId = AuthContext.getUserId();
        if (StringUtils.isBlank(idempotencyKey) || ownerId == null
                || request.getContentLengthLong() > MAX_BODY_BYTES) {
            // 无键 / 未认证（交认证过滤器）/ 明显超限（交大小限额过滤器）→ 普通创建
            filterChain.doFilter(request, response);
            return;
        }
        String key = idempotencyKey.trim();
        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyRequest cachedBodyRequest = new CachedBodyRequest(request, body);
        if (body.length > MAX_BODY_BYTES) {
            filterChain.doFilter(cachedBodyRequest, response);
            return;
        }
        String requestHash = sha256Hex(body);
        Optional<IdempotencyRecord> existing = idempotencyRecordStore.find(ownerId, SCOPE_POST_AGENTS, key);
        if (existing.isPresent()) {
            replayOrReject(response, existing.get(), requestHash);
            return;
        }
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(cachedBodyRequest, responseWrapper);
        } finally {
            persistIfSuccessful(responseWrapper, ownerId, key, requestHash);
            responseWrapper.copyBodyToResponse();
        }
    }

    /** 命中首次记录：摘要一致回放首次结果，否则同键异体冲突（409）。 */
    private void replayOrReject(HttpServletResponse response, IdempotencyRecord record,
                                String requestHash) throws IOException {
        if (!record.requestHash().equals(requestHash)) {
            response.setStatus(HttpStatus.CONFLICT.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(ERROR_ENVELOPE_MAPPER.writeValueAsString(
                    ErrorEnvelope.of(ErrorType.CONFLICT_ERROR,
                            "Idempotency-Key 已被不同的请求体使用")));
            return;
        }
        response.setStatus(record.responseStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(record.responseBody());
    }

    /** 仅登记 2xx 首次结果（失败不缓存，允许客户端重试）。 */
    private void persistIfSuccessful(ContentCachingResponseWrapper responseWrapper, Long ownerId,
                                     String key, String requestHash) {
        int status = responseWrapper.getStatus();
        if (status < 200 || status >= 300) {
            return;
        }
        idempotencyRecordStore.save(new IdempotencyRecord(
                key, ownerId, SCOPE_POST_AGENTS, requestHash, status,
                new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8)));
    }

    /** 是否为受保护端点（POST + 路径以 {@code /agents} 结尾）。 */
    private static boolean isProtected(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.endsWith(PROTECTED_PATH_SUFFIX);
    }

    /** 请求体 SHA-256 十六进制摘要。 */
    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 强制实现，不可达兜底
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }

    /**
     * 可重读请求包装器：把已读入内存的请求体以新的输入流重复暴露给下游，
     * 避免一次性读取消耗原始流导致下游读空（{@code ContentCachingRequestWrapper} 不具备该语义）。
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** 基于字节数组的可重读 ServletInputStream（阻塞读取，不支持异步监听）。 */
    private static final class ByteArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private ByteArrayServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("幂等过滤器不支持异步读取");
        }
    }
}