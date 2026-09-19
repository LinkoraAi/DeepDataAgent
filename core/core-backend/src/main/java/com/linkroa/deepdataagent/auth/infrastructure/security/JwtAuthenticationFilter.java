package com.linkroa.deepdataagent.auth.infrastructure.security;

import com.linkroa.deepdataagent.auth.application.port.RevokedTokenStore;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.result.ErrorEnvelope;
import com.linkroa.deepdataagent.shared.result.ErrorType;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * JWT 无状态校验过滤器。
 * <p>对受保护接口校验 {@code Authorization: Bearer <token>}：HS256 签名与有效期，
 * 并检查 token 是否已被登出撤销（{@code jti} 黑名单）；解析 {@code sub} 得到数字
 * user_id 写入 {@link AuthContext}；缺失 / 非法 / 过期 / 已撤销返回 401。
 * 仅放行精确的公开端点（注册 / 登录）、外部 webhook 回调与受限的 actuator
 * 健康探针（show-details=never，不外泄组件详情）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 公开认证端点（精确匹配，避免整段前缀放行）。 */
    private static final Set<String> PUBLIC_AUTH_ENDPOINTS = Set.of(
            "/api/auth/register",
            "/api/auth/login"
    );

    /**
     * MCP OAuth 回调端点（精确匹配）：授权服务器把浏览器跳转回此处，<b>不携带 Bearer 头</b>，
     * 授权约束由发起授权时下发的一次性 state 承担（TTL + owner 绑定 + origin 记录），
     * 故此处放行不会引入越权面。版本段随 {@link ApiVersionConstants#CURRENT_API_VERSION} 派生。
     */
    private static final String OAUTH_CALLBACK_PATH =
            "/api/v" + ApiVersionConstants.CURRENT_API_VERSION + "/cloud/vaults/oauth/callback";

    /** 健康探针免认证（show-details=never，响应仅含 status，不泄露组件详情）。 */
    private static final String[] PUBLIC_ACTUATOR_PREFIXES = {
            "/actuator/health",
            "/actuator/info"
    };

    /** 外部回调 / 公共路径前缀（无用户身份，仅存在性前缀放行）。 */
    private static final String[] PUBLIC_PATH_PREFIXES = {
            "/api/v" + ApiVersionConstants.CURRENT_API_VERSION + "/cloud/webhook/"
    };

    /** 错误信封序列化用 Mapper（统一形状经 ErrorEnvelope 装配，不手拼 JSON）。 */
    private static final ObjectMapper ERROR_ENVELOPE_MAPPER = JsonMapper.builder().build();

    private final JwtTokenProvider tokenProvider;
    private final RevokedTokenStore revokedTokenStore;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, RevokedTokenStore revokedTokenStore) {
        this.tokenProvider = tokenProvider;
        this.revokedTokenStore = revokedTokenStore;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            if (isPublicOrPreflight(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            String token = JwtTokenProvider.resolveBearerToken(request.getHeader("Authorization"));
            if (token == null) {
                reject(response);
                return;
            }
            try {
                Claims claims = tokenProvider.parseClaims(token);
                if (revokedTokenStore.isRevoked(claims.getId())) {
                    reject(response);
                    return;
                }
                AuthContext.setUserId(Long.parseLong(claims.getSubject()));
            } catch (JwtException | IllegalArgumentException e) {
                reject(response);
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private boolean isPublicOrPreflight(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (PUBLIC_AUTH_ENDPOINTS.contains(uri) || OAUTH_CALLBACK_PATH.equals(uri)) {
            return true;
        }
        for (String prefix : PUBLIC_ACTUATOR_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response) throws IOException {
        // 统一错误信封（shared/api-conventions）：401 authentication_error，request_id 一次性随机
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ERROR_ENVELOPE_MAPPER.writeValueAsString(
                ErrorEnvelope.of(ErrorType.AUTHENTICATION_ERROR, "未认证或凭证已失效")));
    }
}