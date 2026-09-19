package com.linkroa.deepdataagent.vault.application.service;

import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.application.command.StartVaultOAuthCommand;
import com.linkroa.deepdataagent.vault.application.convert.VaultCredentialMaterialConvert;
import com.linkroa.deepdataagent.vault.application.dto.HttpDiagnosticDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthCallbackResultDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthClientRegistrationDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthDiscoveryDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthStartResultDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthTokenOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.dto.VaultOAuthStateDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthDiscoveryPort;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthStateStore;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthTokenExchangePort;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialTokenEndpointAuth;
import com.linkroa.deepdataagent.vault.domain.model.VaultOAuthCodeExchange;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import com.linkroa.deepdataagent.vault.infrastructure.config.VaultOAuthProperties;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;

/**
 * MCP OAuth 授权流转应用服务（发起授权 / 回调落库，见公开契约「发起 MCP OAuth」与 design D7）。
 *
 * <p><b>发起授权</b>：对 active Vault 与给定 {@code mcp_server_url} 执行授权服务器 metadata
 * discovery，准备 PKCE（{@code S256}），必要时执行动态客户端注册，返回授权地址与一次性
 * state。请求不可携带 {@code protocol} / {@code scope} / {@code redirect_uri}——端点与 scopes
 * 由 metadata 发现、回调地址取服务端配置（协议层白名单拒绝）。</p>
 *
 * <p><b>回调落库</b>：原子消费一次性 state（重放 / 过期 / 从未存在一律失败），以授权码换取
 * 令牌，在同一 Vault 下创建 {@code mcp_oauth} 凭证；目标 URL 已有 active 凭证时 409。
 * 响应不含任何令牌密文。</p>
 *
 * <p><b>明文边界</b>：客户端密钥与 PKCE 校验串不进响应与日志；客户端密钥在写入 Redis state
 * 载荷前先加密（Redis 中也无明文），仅在换码调用栈内解密到内存。</p>
 */
@Service
public class VaultOAuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(VaultOAuthApplicationService.class);

    /** 凭证业务 ID 前缀（与 {@code VaultApplicationService} 同口径）。 */
    private static final String CREDENTIAL_ID_PREFIX = "vcred_";

    /** PKCE 校验串随机字节数（base64url 后 43 字符，落在 RFC 7636 的 43–128 区间内）。 */
    private static final int CODE_VERIFIER_BYTES = 32;

    /** state 随机字节数（不透明短期值）。 */
    private static final int STATE_BYTES = 32;

    /** 公开客户端令牌端点鉴权方式（PKCE 承担客户端证明）。 */
    private static final String AUTH_METHOD_NONE = "none";
    /** 携带密钥的令牌端点鉴权方式（按授权服务器声明择优）。 */
    private static final String AUTH_METHOD_BASIC = "client_secret_basic";
    private static final String AUTH_METHOD_POST = "client_secret_post";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Resource
    private VaultRepository vaultRepository;
    @Resource
    private VaultCredentialRepository credentialRepository;
    @Resource
    private VaultCredentialCipherPort cipherPort;
    @Resource
    private VaultOAuthDiscoveryPort discoveryPort;
    @Resource
    private VaultOAuthStateStore stateStore;
    @Resource
    private VaultOAuthTokenExchangePort tokenExchangePort;
    @Resource
    private VaultOAuthProperties oauthProperties;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 发起授权：discovery → PKCE（S256）→（必要时）动态客户端注册 → 生成一次性 state。
     *
     * @param command 发起授权命令（vault_id / mcp_server_url / 可选 client_id、client_secret）
     * @return 授权地址、一次性 state 与回调地址来源
     * @throws ResourceNotFoundException  Vault 不存在 / 非 owner（404）
     * @throws ResourceConflictException  Vault 已归档（409）
     * @throws IllegalArgumentException   无法发现 metadata / 不支持 PKCE S256 或必需鉴权方式 / 注册失败（400）
     */
    public OAuthStartResultDTO start(StartVaultOAuthCommand command) {
        Long ownerId = AuthContext.requireUserId();
        requireActiveOwned(command.vaultId(), ownerId);

        OAuthDiscoveryDTO discovery = discoveryPort.discover(command.mcpServerUrl())
                .orElseThrow(() -> new IllegalArgumentException(
                        "无法发现该 MCP 服务器对应的授权服务器 metadata: " + command.mcpServerUrl()));
        if (!discovery.supportsS256()) {
            // PKCE S256 是发起授权的必需行为：授权服务器不支持即无法安全发起（不产生凭证）
            throw new IllegalArgumentException("授权服务器不支持 PKCE S256，无法发起授权");
        }

        // 回调地址一律取服务端配置，MUST NOT 由请求覆盖
        String redirectUri = oauthProperties.getCallbackUrl();
        RegisteredClient client = resolveClient(command, discovery, redirectUri);

        String codeVerifier = randomToken(CODE_VERIFIER_BYTES);
        String state = randomToken(STATE_BYTES);
        // 来源：前端 Origin 优先，缺失回落服务端回调来源（回调页面据此确定 postMessage 目标源）
        String origin = StringUtils.isNotBlank(command.origin())
                ? command.origin() : oauthProperties.callbackOrigin();
        stateStore.save(state, new VaultOAuthStateDTO(
                ownerId,
                command.vaultId(),
                command.mcpServerUrl(),
                client.clientId(),
                encryptSecret(client.clientSecret()),
                codeVerifier,
                discovery.tokenEndpoint(),
                client.authType().getValue(),
                redirectUri,
                discovery.resource(),
                discovery.scopeParameter(),
                origin),
                oauthProperties.stateTtl());

        return new OAuthStartResultDTO(
                buildAuthorizationUrl(discovery, client.clientId(), redirectUri, codeVerifier, state),
                state,
                oauthProperties.callbackOrigin());
    }

    /**
     * 回调：一次性消费 state → 授权码换令牌 → 在该 Vault 下创建 {@code mcp_oauth} 凭证。
     *
     * <p>回调由浏览器跳转触发（无认证上下文），授权约束由 state 承担：state 归属的 owner
     * 与目标保管库归属不一致一律拒绝，凭证恒落在 state 记录的 owner 名下。</p>
     *
     * @param code  授权服务器回调携带的授权码
     * @param state 发起授权时下发的一次性 state
     * @return 落库凭证标识（不含任何令牌密文）
     * @throws IllegalArgumentException    state 无效（重放 / 过期 / 伪造）/ 缺少授权码 / 换码失败（400）
     * @throws ResourceNotFoundException   state 归属与目标保管库不匹配（404）
     * @throws ResourceConflictException   该 Vault 下目标 URL 已有 active 凭证（409）
     */
    public OAuthCallbackResultDTO callback(String code, String state) {
        if (StringUtils.isBlank(state)) {
            throw new IllegalArgumentException("回调缺少 state");
        }
        // 原子读删：重放 / 过期 / 从未存在的 state 一律在此被拒（不产生凭证）
        VaultOAuthStateDTO stateData = stateStore.consume(state)
                .orElseThrow(() -> new IllegalArgumentException("授权 state 无效、已过期或已被使用，请重新发起授权"));
        if (StringUtils.isBlank(code)) {
            throw new IllegalArgumentException("回调缺少授权码");
        }
        requireActiveOwned(stateData.vaultId(), stateData.ownerId());
        if (credentialRepository.existsActiveByVaultIdAndUrl(stateData.vaultId(), stateData.mcpServerUrl())) {
            throw new ResourceConflictException("该 MCP server 已存在活跃凭证，请先归档或更新原凭证");
        }

        VaultCredentialTokenEndpointAuthType authType =
                VaultCredentialTokenEndpointAuthType.fromValue(stateData.tokenEndpointAuthType());
        String clientSecret = decryptSecret(stateData.clientSecretCiphertext());
        OAuthTokenOutcomeDTO outcome = tokenExchangePort.exchangeAuthorizationCode(new VaultOAuthCodeExchange(
                stateData.tokenEndpoint(), stateData.clientId(), clientSecret, authType,
                code, stateData.codeVerifier(), stateData.redirectUri(), stateData.resource()));
        if (StringUtils.isBlank(outcome.accessToken())) {
            throw new IllegalArgumentException("授权码换取令牌失败：" + failureHint(outcome.httpResponse()));
        }

        VaultCredentialMaterial material = new VaultCredentialMaterial(outcome.accessToken(),
                buildRefresh(stateData, outcome, authType, clientSecret));
        byte[] ciphertext = cipherPort.encrypt(VaultCredentialMaterialConvert.INSTANCE.toEnvelopeJson(material));
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        VaultCredential saved = transactionTemplate.execute(status -> credentialRepository.save(
                VaultCredential.createWithExpiry(CREDENTIAL_ID_PREFIX + UUID.randomUUID(), stateData.vaultId(),
                        VaultCredentialAuthType.MCP_OAUTH, stateData.mcpServerUrl(), ciphertext,
                        expiresAtOf(outcome, now))));
        return new OAuthCallbackResultDTO(saved.vaultId(), saved.credentialId(), stateData.origin());
    }

    /**
     * 组装刷新配置：仅当授权服务器下发刷新令牌时产出（无刷新令牌的凭证不可刷新）。
     */
    private VaultCredentialRefresh buildRefresh(VaultOAuthStateDTO stateData, OAuthTokenOutcomeDTO outcome,
                                                VaultCredentialTokenEndpointAuthType authType,
                                                String clientSecret) {
        if (StringUtils.isBlank(outcome.refreshToken())) {
            return null;
        }
        return new VaultCredentialRefresh(
                stateData.clientId(),
                outcome.refreshToken(),
                stateData.tokenEndpoint(),
                new VaultCredentialTokenEndpointAuth(authType, clientSecret),
                stateData.resource(),
                stateData.scope());
    }

    /**
     * 到期时间：响应携带 {@code expires_in} 时按当前时刻重算，否则无到期（null）。
     */
    private static OffsetDateTime expiresAtOf(OAuthTokenOutcomeDTO outcome, OffsetDateTime now) {
        return outcome.expiresInSeconds() == null ? null : now.plusSeconds(outcome.expiresInSeconds());
    }

    /**
     * 解析客户端身份：显式 {@code client_id} 直接复用；否则在授权服务器声明注册端点时动态注册。
     */
    private RegisteredClient resolveClient(StartVaultOAuthCommand command, OAuthDiscoveryDTO discovery,
                                           String redirectUri) {
        if (command.hasClientId()) {
            return new RegisteredClient(command.clientId(), command.clientSecret(),
                    resolveAuthType(discovery, command.hasClientSecret()));
        }
        if (!discovery.supportsDynamicRegistration()) {
            throw new IllegalArgumentException("未提供 client_id 且授权服务器未声明注册端点，无法发起授权");
        }
        // 未提供客户端身份时一律以公开客户端（none）注册，PKCE 承担客户端证明
        resolveAuthType(discovery, false);
        OAuthClientRegistrationDTO registered = discoveryPort.registerClient(discovery.registrationEndpoint(),
                        redirectUri, oauthProperties.getClientName(), AUTH_METHOD_NONE, discovery.scopeParameter())
                .orElseThrow(() -> new IllegalArgumentException("动态客户端注册失败，请提供 client_id"));
        boolean hasSecret = StringUtils.isNotBlank(registered.clientSecret());
        return new RegisteredClient(registered.clientId(), registered.clientSecret(),
                resolveAuthType(discovery, hasSecret));
    }

    /**
     * 按授权服务器声明择优令牌端点鉴权方式；无可用的必需方式即 400
     * （契约「授权服务器不支持必需行为」场景）。
     */
    private static VaultCredentialTokenEndpointAuthType resolveAuthType(OAuthDiscoveryDTO discovery,
                                                                        boolean hasClientSecret) {
        if (hasClientSecret) {
            if (discovery.supportsTokenEndpointAuthMethod(AUTH_METHOD_BASIC)) {
                return VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC;
            }
            if (discovery.supportsTokenEndpointAuthMethod(AUTH_METHOD_POST)) {
                return VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_POST;
            }
            throw new IllegalArgumentException(
                    "授权服务器不支持 client_secret_basic / client_secret_post 令牌端点鉴权方式");
        }
        if (discovery.supportsTokenEndpointAuthMethod(AUTH_METHOD_NONE)) {
            return VaultCredentialTokenEndpointAuthType.NONE;
        }
        throw new IllegalArgumentException("授权服务器不支持 none 令牌端点鉴权（公开客户端），请提供 client_secret");
    }

    /**
     * 组装授权地址：{@code response_type=code} + {@code code_challenge_method=S256} + state，
     * scopes 与受保护资源标识取 metadata 发现所得（请求不可覆盖）。
     */
    private static String buildAuthorizationUrl(OAuthDiscoveryDTO discovery, String clientId, String redirectUri,
                                                String codeVerifier, String state) {
        StringBuilder url = new StringBuilder(discovery.authorizationEndpoint());
        appendParam(url, "response_type", "code");
        appendParam(url, "client_id", clientId);
        appendParam(url, "redirect_uri", redirectUri);
        appendParam(url, "code_challenge", codeChallengeOf(codeVerifier));
        appendParam(url, "code_challenge_method", OAuthDiscoveryDTO.PKCE_METHOD_S256);
        appendParam(url, "state", state);
        appendParam(url, "scope", discovery.scopeParameter());
        appendParam(url, "resource", discovery.resource());
        return url.toString();
    }

    /** 追加查询参数（名与值均百分号编码；值为空白时跳过；分隔符按是否已含查询串自行裁决）。 */
    private static void appendParam(StringBuilder url, String name, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        char last = url.charAt(url.length() - 1);
        if (last != '?' && last != '&') {
            url.append(url.indexOf("?") >= 0 ? '&' : '?');
        }
        url.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /** PKCE 挑战：{@code BASE64URL(SHA-256(ASCII(code_verifier)))}，无填充（RFC 7636 S256）。 */
    private static String codeChallengeOf(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }

    /** 随机不透明值（base64url 无填充）。 */
    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        SECURE_RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /** 客户端密钥加密为 Base64 密文（空白直接透传 null，Redis 中不留明文）。 */
    private String encryptSecret(String clientSecret) {
        if (StringUtils.isBlank(clientSecret)) {
            return null;
        }
        return Base64.getEncoder().encodeToString(cipherPort.encrypt(clientSecret));
    }

    /** 客户端密钥密文解密（空白返回 null）。 */
    private String decryptSecret(String ciphertext) {
        if (StringUtils.isBlank(ciphertext)) {
            return null;
        }
        return cipherPort.decrypt(Base64.getDecoder().decode(ciphertext));
    }

    /** 换码失败提示（只含分类与状态码，不含响应原文）。 */
    private static String failureHint(HttpDiagnosticDTO response) {
        return response == null ? "未收到授权服务器响应" : "HTTP " + response.statusCode();
    }

    /**
     * owner 隔离 + 归档过滤（含 409 语义）：非 owner / 不存在 → 404；已归档 → 409
     * （契约「Vault 非 active → 409」场景）。
     */
    private void requireActiveOwned(String vaultId, Long ownerId) {
        Vault vault = vaultRepository.findByVaultId(vaultId)
                .orElseThrow(() -> new ResourceNotFoundException("保管库不存在"));
        if (!vault.ownerId().equals(ownerId)) {
            throw new ResourceNotFoundException("保管库不存在");
        }
        if (vault.archived()) {
            throw new ResourceConflictException("已归档保管库不可发起 OAuth 授权");
        }
    }

    /**
     * 解析所得客户端身份（客户端 ID + 可空密钥 + 令牌端点鉴权方式）。
     */
    private record RegisteredClient(String clientId, String clientSecret,
                                    VaultCredentialTokenEndpointAuthType authType) {

        private RegisteredClient {
            if (StringUtils.isBlank(clientId)) {
                throw new IllegalArgumentException("客户端 ID 不能为空");
            }
            if (authType == null) {
                throw new IllegalArgumentException("令牌端点鉴权方式不能为空");
            }
        }
    }
}