package com.linkroa.deepdataagent.vault.infrastructure.client;

import com.linkroa.deepdataagent.shared.config.EgressProperties;
import com.linkroa.deepdataagent.shared.net.TrustedEgressClient;
import com.linkroa.deepdataagent.vault.application.dto.OAuthClientRegistrationDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthDiscoveryDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthDiscoveryPort;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP OAuth metadata 发现与动态客户端注册出站实现（{@link VaultOAuthDiscoveryPort}，见 design D8）。
 *
 * <p><b>发现顺序</b>（RFC 9728 → RFC 8414，均按「well-known 插在主机与路径之间」的形态取址）：
 * ① 取 MCP 服务器（受保护资源）的 {@code /.well-known/oauth-protected-resource} 定位授权服务器
 * （未声明或不可达时回落以 MCP 服务器来源为 issuer）；② 取授权服务器 metadata——依次尝试
 * {@code oauth-authorization-server} 与 {@code openid-configuration} 两种 well-known 形态，
 * 首个「2xx 且含授权 / 令牌端点」的报文胜出。</p>
 *
 * <p>出网统一经 {@link TrustedEgressClient}（单次解析 + 逐跳复检 + 越界零请求），
 * 故 discovery 与注册端点同样受出网信任边界约束；任何失败一律以空结果承载，由应用层按 400 拒绝。</p>
 */
@Service
public class TrustedOAuthDiscoveryClient implements VaultOAuthDiscoveryPort {

    private static final Logger log = LoggerFactory.getLogger(TrustedOAuthDiscoveryClient.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 受保护资源 metadata 的 well-known 段（RFC 9728）。 */
    private static final String PROTECTED_RESOURCE_WELL_KNOWN = "/.well-known/oauth-protected-resource";
    /** 授权服务器 metadata 的 well-known 段（RFC 8414）。 */
    private static final String AUTHORIZATION_SERVER_WELL_KNOWN = "/.well-known/oauth-authorization-server";
    /** OIDC discovery 的 well-known 段（授权服务器 metadata 的兼容形态）。 */
    private static final String OPENID_CONFIGURATION_WELL_KNOWN = "/.well-known/openid-configuration";

    @Resource
    private EgressProperties egressProperties;

    @Override
    public Optional<OAuthDiscoveryDTO> discover(String mcpServerUrl) {
        URI resource = parseHttpUri(mcpServerUrl);
        if (resource == null) {
            log.debug("MCP 服务器 URL 不是合法 http(s) 地址，无法发现授权服务器 metadata");
            return Optional.empty();
        }
        JsonNode protectedResource = fetchRaw(wellKnownBeforePath(resource, PROTECTED_RESOURCE_WELL_KNOWN));
        String authorizationServer = text(protectedResource, "authorization_servers", 0);
        String issuer = StringUtils.isNotBlank(authorizationServer) ? authorizationServer : originOf(resource);
        Optional<JsonNode> metadata = fetchAuthorizationServerMetadata(issuer);
        if (metadata.isEmpty()) {
            // 授权服务器 metadata 不可达 / 报文缺端点：不接受半残发现结果
            log.debug("授权服务器 metadata 发现失败: issuer={}", issuer);
            return Optional.empty();
        }
        return Optional.of(new OAuthDiscoveryDTO(
                text(metadata.get(), "authorization_endpoint"),
                text(metadata.get(), "token_endpoint"),
                text(metadata.get(), "registration_endpoint"),
                textList(metadata.get(), "code_challenge_methods_supported"),
                textList(metadata.get(), "token_endpoint_auth_methods_supported"),
                textList(metadata.get(), "scopes_supported"),
                text(protectedResource, "resource")));
    }

    @Override
    public Optional<OAuthClientRegistrationDTO> registerClient(String registrationEndpoint, String redirectUri,
                                                               String clientName, String tokenEndpointAuthMethod,
                                                               String scope) {
        URI endpoint = parseHttpUri(registrationEndpoint);
        if (endpoint == null) {
            return Optional.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", clientName);
        payload.put("redirect_uris", List.of(redirectUri));
        payload.put("grant_types", List.of("authorization_code", "refresh_token"));
        payload.put("response_types", List.of("code"));
        payload.put("token_endpoint_auth_method", tokenEndpointAuthMethod);
        if (StringUtils.isNotBlank(scope)) {
            payload.put("scope", scope);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        JsonNode response = send(new TrustedEgressClient.Request("POST", endpoint, headers,
                HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8)));
        String clientId = text(response, "client_id");
        if (StringUtils.isBlank(clientId)) {
            log.debug("动态客户端注册未返回 client_id");
            return Optional.empty();
        }
        return Optional.of(new OAuthClientRegistrationDTO(clientId, text(response, "client_secret")));
    }

    /**
     * 取授权服务器 metadata：依次尝试 RFC 8414 与 OIDC 的 well-known 取址形态，
     * 首个「2xx 且含授权 / 令牌端点」的报文胜出（无命中返回空）。
     */
    private Optional<JsonNode> fetchAuthorizationServerMetadata(String issuer) {
        URI issuerUri = parseHttpUri(issuer);
        if (issuerUri == null) {
            return Optional.empty();
        }
        for (URI candidate : List.of(
                wellKnownBeforePath(issuerUri, AUTHORIZATION_SERVER_WELL_KNOWN),
                wellKnownBeforePath(issuerUri, OPENID_CONFIGURATION_WELL_KNOWN),
                wellKnownAfterPath(issuerUri, AUTHORIZATION_SERVER_WELL_KNOWN),
                wellKnownAfterPath(issuerUri, OPENID_CONFIGURATION_WELL_KNOWN))) {
            JsonNode metadata = fetchRaw(candidate);
            if (metadata != null && StringUtils.isNotBlank(text(metadata, "authorization_endpoint"))
                    && StringUtils.isNotBlank(text(metadata, "token_endpoint"))) {
                return Optional.of(metadata);
            }
        }
        return Optional.empty();
    }

    /** 发起一次 GET 并解析 JSON 对象（非 2xx / 非 JSON / 不可达一律 null）。 */
    private JsonNode fetchRaw(URI target) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        return send(new TrustedEgressClient.Request("GET", target, headers, null));
    }

    /** 发起出网请求并解析 JSON 对象（失败返回 null；越界零请求）。 */
    private JsonNode send(TrustedEgressClient.Request request) {
        TrustedEgressClient.Result result = TrustedEgressClient.send(request,
                egressProperties.isAllowPrivateNetwork(), egressProperties.getMaxRedirects());
        if (result.failed()) {
            log.debug("OAuth metadata 出网未收到响应: reason={}", result.failureReason());
            return null;
        }
        TrustedEgressClient.Response response = result.response();
        if (response.statusCode() < 200 || response.statusCode() >= 300 || StringUtils.isBlank(response.body())) {
            return null;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(response.body());
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (RuntimeException e) {
            log.debug("OAuth metadata 报文非法 JSON: reason={}", e.getMessage());
            return null;
        }
    }

    /** 序列化注册请求体（JSON 文本，仅含回调地址与客户端名，不含任何凭证材料）。 */
    private static String writeJson(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("客户端注册请求体序列化失败: " + e.getMessage(), e);
        }
    }

    /** well-known 取址（RFC 8414 / 9728 形态）：{@code scheme://authority}{@code wellKnown}{@code path}。 */
    private static URI wellKnownBeforePath(URI uri, String wellKnown) {
        return URI.create(uri.getScheme() + "://" + uri.getAuthority() + wellKnown + normalizedPath(uri));
    }

    /** well-known 取址（OIDC 形态）：{@code issuer}{@code wellKnown}。 */
    private static URI wellKnownAfterPath(URI uri, String wellKnown) {
        return URI.create(trimTrailingSlash(uri.toString()) + wellKnown);
    }

    /** 路径段（空路径返回空串；去掉末尾斜杠避免出现双斜杠）。 */
    private static String normalizedPath(URI uri) {
        String path = uri.getPath();
        return StringUtils.isBlank(path) || "/".equals(path) ? "" : trimTrailingSlash(path);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 来源（{@code scheme://authority}），作为未声明授权服务器时的 issuer 回落值。 */
    private static String originOf(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** 解析绝对 http(s) 目标（非法 / 非 http(s) / 缺主机返回 null）。 */
    private static URI parseHttpUri(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!uri.isAbsolute() || StringUtils.isBlank(uri.getAuthority())) {
                return null;
            }
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 文本字段取值（缺失 / null / 非文本返回 null）。 */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isString() ? null : value.asString();
    }

    /** 数组字段第 index 项文本取值（缺失 / 越界 / 非文本返回 null）。 */
    private static String text(JsonNode node, String field, int index) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() <= index) {
            return null;
        }
        JsonNode item = value.get(index);
        return item == null || !item.isString() ? null : item.asString();
    }

    /** 字符串数组字段取值（缺失 / 非数组返回空列表；非文本元素忽略）。 */
    private static List<String> textList(JsonNode node, String field) {
        if (node == null) {
            return List.of();
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (item != null && item.isString()) {
                result.add(item.asString());
            }
        });
        return result;
    }
}