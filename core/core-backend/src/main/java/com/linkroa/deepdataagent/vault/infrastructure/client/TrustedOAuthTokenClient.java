package com.linkroa.deepdataagent.vault.infrastructure.client;

import com.linkroa.deepdataagent.shared.config.EgressProperties;
import com.linkroa.deepdataagent.shared.net.TrustedEgressClient;
import com.linkroa.deepdataagent.vault.application.dto.HttpDiagnosticDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthRefreshOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthTokenOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthRefreshPort;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthTokenExchangePort;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;
import com.linkroa.deepdataagent.vault.domain.model.VaultOAuthCodeExchange;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * OAuth 令牌端点出站实现（{@link VaultOAuthRefreshPort} + {@link VaultOAuthTokenExchangePort}，
 * 见 design D4 / D8）：刷新与换码共用同一段「表单 POST + 客户端鉴权 + 令牌报文解析」逻辑，
 * 差别只在 {@code grant_type} 与请求参数集合。
 *
 * <p>按 OAuth 2.1 令牌端点语义：客户端鉴权方式为 {@code client_secret_basic} 时以 HTTP Basic
 * 携带密钥、{@code client_secret_post} 时以表单参数携带、{@code none} 时不携带密钥。
 * 出网统一经 {@link TrustedEgressClient}（单次解析 + 逐跳复检 + 越界零请求），
 * 故令牌端点同样受出网信任边界约束。</p>
 *
 * <p><b>明文边界</b>：刷新令牌 / 授权码 / PKCE 校验串 / 客户端密钥仅存在于请求头与请求体
 * （内存与网络），不落库、不进响应与日志；回传的响应体已脱敏截断。</p>
 */
@Service
public class TrustedOAuthTokenClient implements VaultOAuthRefreshPort, VaultOAuthTokenExchangePort {

    private static final Logger log = LoggerFactory.getLogger(TrustedOAuthTokenClient.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 表单参数名（OAuth 2.1 令牌端点固定词汇）。 */
    private static final String GRANT_TYPE_PARAM = "grant_type";
    private static final String REFRESH_TOKEN_PARAM = "refresh_token";
    private static final String CLIENT_ID_PARAM = "client_id";
    private static final String CLIENT_SECRET_PARAM = "client_secret";
    private static final String RESOURCE_PARAM = "resource";
    private static final String SCOPE_PARAM = "scope";
    private static final String CODE_PARAM = "code";
    private static final String CODE_VERIFIER_PARAM = "code_verifier";
    private static final String REDIRECT_URI_PARAM = "redirect_uri";

    /** 授权类型。 */
    private static final String REFRESH_GRANT_TYPE = "refresh_token";
    private static final String AUTHORIZATION_CODE_GRANT_TYPE = "authorization_code";

    @Resource
    private EgressProperties egressProperties;

    @Override
    public OAuthRefreshOutcomeDTO refresh(VaultCredentialRefresh refresh) {
        URI tokenEndpoint = parseUri(refresh.tokenEndpoint());
        if (tokenEndpoint == null) {
            log.debug("刷新令牌端点不可用，跳过刷新: reason=非法URL");
            return new OAuthRefreshOutcomeDTO(null, null, null, null);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put(GRANT_TYPE_PARAM, REFRESH_GRANT_TYPE);
        form.put(REFRESH_TOKEN_PARAM, refresh.refreshToken());
        form.put(CLIENT_ID_PARAM, refresh.clientId());
        putIfPresent(form, RESOURCE_PARAM, refresh.resource());
        putIfPresent(form, SCOPE_PARAM, refresh.scope());

        Optional<TokenHttpResult> result = callTokenEndpoint(tokenEndpoint, form, refresh.clientId(),
                refresh.tokenEndpointAuth().type(), refresh.tokenEndpointAuth().clientSecret());
        if (result.isEmpty()) {
            return new OAuthRefreshOutcomeDTO(null, null, null, null);
        }
        TokenHttpResult http = result.get();
        TokenPayload payload = http.payload();
        return new OAuthRefreshOutcomeDTO(payload.accessToken(), payload.refreshToken(),
                payload.expiresInSeconds(), http.diagnostic());
    }

    @Override
    public OAuthTokenOutcomeDTO exchangeAuthorizationCode(VaultOAuthCodeExchange exchange) {
        URI tokenEndpoint = parseUri(exchange.tokenEndpoint());
        if (tokenEndpoint == null) {
            log.debug("换码令牌端点不可用: reason=非法URL");
            return new OAuthTokenOutcomeDTO(null, null, null, null);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put(GRANT_TYPE_PARAM, AUTHORIZATION_CODE_GRANT_TYPE);
        form.put(CODE_PARAM, exchange.code());
        form.put(REDIRECT_URI_PARAM, exchange.redirectUri());
        form.put(CLIENT_ID_PARAM, exchange.clientId());
        form.put(CODE_VERIFIER_PARAM, exchange.codeVerifier());
        putIfPresent(form, RESOURCE_PARAM, exchange.resource());

        Optional<TokenHttpResult> result = callTokenEndpoint(tokenEndpoint, form, exchange.clientId(),
                exchange.tokenEndpointAuthType(), exchange.clientSecret());
        if (result.isEmpty()) {
            return new OAuthTokenOutcomeDTO(null, null, null, null);
        }
        TokenHttpResult http = result.get();
        TokenPayload payload = http.payload();
        return new OAuthTokenOutcomeDTO(payload.accessToken(), payload.refreshToken(),
                payload.expiresInSeconds(), http.diagnostic());
    }

    /**
     * 令牌端点表单 POST（含客户端鉴权装配与令牌报文解析）。
     *
     * @return 收到响应时的诊断 + 令牌载荷（非 2xx 时载荷全空）；未收到任何响应时为空
     */
    private Optional<TokenHttpResult> callTokenEndpoint(URI tokenEndpoint, Map<String, String> form,
                                                        String clientId,
                                                        VaultCredentialTokenEndpointAuthType authType,
                                                        String clientSecret) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        applyClientAuthentication(authType, clientId, clientSecret, form, headers);

        TrustedEgressClient.Request request = new TrustedEgressClient.Request("POST", tokenEndpoint, headers,
                HttpRequest.BodyPublishers.ofString(toFormBody(form), StandardCharsets.UTF_8));
        TrustedEgressClient.Result result = TrustedEgressClient.send(request,
                egressProperties.isAllowPrivateNetwork(), egressProperties.getMaxRedirects());
        if (result.failed()) {
            // 失败原因只含目标与异常摘要（URL 由用户提交，不含凭证材料）
            log.debug("令牌端点请求未收到响应: reason={}", result.failureReason());
            return Optional.empty();
        }
        TrustedEgressClient.Response response = result.response();
        HttpDiagnosticDTO diagnostic = new HttpDiagnosticDTO(response.statusCode(), response.contentType(),
                response.diagnosticBody(), response.diagnosticBodyTruncated());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Optional.of(new TokenHttpResult(diagnostic, TokenPayload.empty()));
        }
        return Optional.of(new TokenHttpResult(diagnostic, parseTokenPayload(response.body())));
    }

    /**
     * 按客户端鉴权方式装配密钥承载（Basic 头 / 表单参数 / 不携带）。
     */
    private static void applyClientAuthentication(VaultCredentialTokenEndpointAuthType authType, String clientId,
                                                  String clientSecret, Map<String, String> form,
                                                  Map<String, String> headers) {
        if (authType == VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC) {
            String credentials = clientId + ":" + clientSecret;
            headers.put("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else if (authType == VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_POST) {
            form.put(CLIENT_SECRET_PARAM, clientSecret);
        }
    }

    /**
     * 解析令牌响应（{@code access_token} 必取；{@code refresh_token} / {@code expires_in} 可选）。
     *
     * <p>响应体不是合法 JSON 或缺 {@code access_token} 时按「未换得令牌」返回
     * （分类交应用层判为 {@code failed} / 换码失败）。</p>
     */
    private static TokenPayload parseTokenPayload(String body) {
        if (StringUtils.isBlank(body)) {
            return TokenPayload.empty();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (RuntimeException e) {
            log.debug("令牌响应体非法 JSON: reason={}", e.getMessage());
            return TokenPayload.empty();
        }
        if (root == null || !root.isObject()) {
            return TokenPayload.empty();
        }
        String accessToken = text(root.get("access_token"));
        if (StringUtils.isBlank(accessToken)) {
            return TokenPayload.empty();
        }
        return new TokenPayload(accessToken, text(root.get("refresh_token")), intOrNull(root.get("expires_in")));
    }

    /** 表单序列化（RFC 3986 百分号编码，参数值含凭证明文，仅进请求体）。 */
    private static String toFormBody(Map<String, String> form) {
        StringBuilder builder = new StringBuilder();
        form.forEach((name, value) -> {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return builder.toString();
    }

    /** 目标 URL 解析（非法 / 缺失返回 null，交由调用方按「未收到响应」处置）。 */
    private static URI parseUri(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 节点文本取值（null 节点返回 null）。 */
    private static String text(JsonNode node) {
        return node == null || node.isNull() || !node.isString() ? null : node.asString();
    }

    /** 节点取整数（数字或数字字符串；无法解析返回 null）。 */
    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asString().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 值非空白时写入表单。 */
    private static void putIfPresent(Map<String, String> form, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            form.put(name, value);
        }
    }

    /**
     * 令牌端点响应（诊断 + 已解析载荷；非 2xx 时载荷为空）。
     */
    private record TokenHttpResult(HttpDiagnosticDTO diagnostic, TokenPayload payload) {
    }

    /**
     * 令牌报文载荷（access_token 为空即「未换得令牌」）。
     */
    private record TokenPayload(String accessToken, String refreshToken, Integer expiresInSeconds) {

        static TokenPayload empty() {
            return new TokenPayload(null, null, null);
        }
    }
}