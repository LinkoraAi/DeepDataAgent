package com.linkroa.deepdataagent.vault.application.service;

import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.application.command.StartVaultOAuthCommand;
import com.linkroa.deepdataagent.vault.application.convert.VaultCredentialMaterialConvert;
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
import com.linkroa.deepdataagent.vault.domain.model.VaultOAuthCodeExchange;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import com.linkroa.deepdataagent.vault.infrastructure.assembly.DefaultVaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.infrastructure.config.VaultEncryptionProperties;
import com.linkroa.deepdataagent.vault.infrastructure.config.VaultOAuthProperties;
import com.linkroa.deepdataagent.vault.infrastructure.util.VaultCredentialEncryptionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VaultOAuthApplicationService} MCP OAuth 流转应用服务单测。
 *
 * <p>覆盖：发起授权（discovery 缺失 / 不支持 S256 / 无可用的令牌端点鉴权方式 → 400；
 * 显式 client_id 复用与动态客户端注册两条取客户端路径；PKCE S256 挑战计算；回调地址恒取
 * 服务端配置；state 载荷 owner 绑定 + 来源记录 + 客户端密钥密文入 Redis）、
 * 回调落库（一次性 state 消费的拒绝语义：缺失 / 重放与过期；跨 owner → 404；目标 URL 已有
 * active 凭证 → 409；换码失败不落库；刷新配置与到期时间组装；响应不含令牌材料）。</p>
 *
 * <p>注：application/service 层按 AGENTS.md 不计入单元测试覆盖率，本测试为行为回归保障。</p>
 */
@ExtendWith(MockitoExtension.class)
class VaultOAuthApplicationServiceTest {

    private static final String MCP_URL = "https://mcp.example.com/sse";
    private static final String ORIGIN = "https://app.example.com";

    @Mock private VaultRepository vaultRepository;
    @Mock private VaultCredentialRepository credentialRepository;
    @Mock private VaultOAuthDiscoveryPort discoveryPort;
    @Mock private VaultOAuthStateStore stateStore;
    @Mock private VaultOAuthTokenExchangePort tokenExchangePort;
    @Mock private TransactionTemplate transactionTemplate;

    private VaultCredentialEncryptionUtil encryptionUtil;
    private VaultOAuthProperties oauthProperties;
    private VaultOAuthApplicationService service;

    @BeforeEach
    void setUp() {
        VaultEncryptionProperties encryptionProperties = new VaultEncryptionProperties();
        encryptionProperties.setKey("test-vault-key");
        encryptionUtil = new VaultCredentialEncryptionUtil(encryptionProperties);
        VaultCredentialCipherPort cipherPort = new DefaultVaultCredentialCipherPort();
        ReflectionTestUtils.setField(cipherPort, "encryptionUtil", encryptionUtil);
        oauthProperties = new VaultOAuthProperties();
        service = new VaultOAuthApplicationService();
        ReflectionTestUtils.setField(service, "vaultRepository", vaultRepository);
        ReflectionTestUtils.setField(service, "credentialRepository", credentialRepository);
        ReflectionTestUtils.setField(service, "cipherPort", cipherPort);
        ReflectionTestUtils.setField(service, "discoveryPort", discoveryPort);
        ReflectionTestUtils.setField(service, "stateStore", stateStore);
        ReflectionTestUtils.setField(service, "tokenExchangePort", tokenExchangePort);
        ReflectionTestUtils.setField(service, "oauthProperties", oauthProperties);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        AuthContext.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    // ==================== start：发起授权 ====================

    @Test
    void should_returnAuthorizationRequest_when_start_given_explicitClientCredentials() {
        // given（显式 client_id / client_secret + 授权服务器声明 basic 鉴权）
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL)).thenReturn(Optional.of(discovery(null, "client_secret_basic")));

        // when
        OAuthStartResultDTO result = service.start(new StartVaultOAuthCommand(
                "vault_1", MCP_URL, "client-1", "cs-1", ORIGIN));

        // then（授权请求要素齐备；scopes / resource / 回调地址一律取服务端侧发现与配置）
        assertEquals("code", queryParam(result.authorizationUrl(), "response_type"));
        assertEquals("client-1", queryParam(result.authorizationUrl(), "client_id"));
        assertEquals("S256", queryParam(result.authorizationUrl(), "code_challenge_method"));
        assertEquals(oauthProperties.getCallbackUrl(), queryParam(result.authorizationUrl(), "redirect_uri"));
        assertEquals("read write", queryParam(result.authorizationUrl(), "scope"));
        assertEquals(MCP_URL, queryParam(result.authorizationUrl(), "resource"));
        assertEquals(result.state(), queryParam(result.authorizationUrl(), "state"));
        assertEquals("http://localhost:8080", result.callbackOrigin());
        assertTrue(result.authorizationUrl().startsWith("https://auth.example.com/authorize?"));
    }

    @Test
    void should_computeS256Challenge_when_start_given_codeVerifier() {
        // given
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL)).thenReturn(Optional.of(discovery(null, "client_secret_basic")));

        // when
        CapturedStart captured = startAndCaptureState(new StartVaultOAuthCommand(
                "vault_1", MCP_URL, "client-1", "cs-1", ORIGIN));

        // then（code_challenge = BASE64URL(SHA-256(ASCII(code_verifier)))，无填充）
        String verifier = captured.payload().codeVerifier();
        assertEquals(43, verifier.length());
        assertEquals(s256ChallengeOf(verifier), queryParam(captured.result().authorizationUrl(), "code_challenge"));
    }

    @Test
    void should_persistStateWithEncryptedSecret_when_start_given_clientSecret() {
        // given
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL)).thenReturn(Optional.of(discovery(null, "client_secret_basic")));

        // when
        CapturedStart captured = startAndCaptureState(new StartVaultOAuthCommand(
                "vault_1", MCP_URL, "client-1", "cs-1", ORIGIN));

        // then（state 载荷承载 owner 绑定与发起来源；客户端密钥不以明文入 Redis）
        VaultOAuthStateDTO payload = captured.payload();
        assertEquals(1L, payload.ownerId());
        assertEquals("vault_1", payload.vaultId());
        assertEquals(MCP_URL, payload.mcpServerUrl());
        assertEquals("client-1", payload.clientId());
        assertNotEquals("cs-1", payload.clientSecretCiphertext());
        assertEquals("cs-1", encryptionUtil.decrypt(
                Base64.getDecoder().decode(payload.clientSecretCiphertext())));
        assertEquals("https://auth.example.com/token", payload.tokenEndpoint());
        assertEquals("client_secret_basic", payload.tokenEndpointAuthType());
        assertEquals(oauthProperties.getCallbackUrl(), payload.redirectUri());
        assertEquals("read write", payload.scope());
        assertEquals(MCP_URL, payload.resource());
        assertEquals(ORIGIN, payload.origin());
        assertEquals(captured.result().state(), captured.state());
        verify(stateStore).save(captured.state(), payload, Duration.ofSeconds(300));
    }

    @Test
    void should_registerPublicClient_when_start_given_noClientIdWithRegistrationEndpoint() {
        // given（未提供 client_id：走动态客户端注册的公开客户端形态）
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL))
                .thenReturn(Optional.of(discovery("https://auth.example.com/register", "none")));
        when(discoveryPort.registerClient("https://auth.example.com/register",
                oauthProperties.getCallbackUrl(), oauthProperties.getClientName(), "none", "read write"))
                .thenReturn(Optional.of(new OAuthClientRegistrationDTO("dyn-1", null)));

        // when
        CapturedStart captured = startAndCaptureState(new StartVaultOAuthCommand("vault_1", MCP_URL, null, null, null));

        // then（注册所得身份进入 state 与授权地址；无 Origin 时来源回落服务端回调来源）
        assertEquals("dyn-1", captured.payload().clientId());
        assertNull(captured.payload().clientSecretCiphertext());
        assertEquals("none", captured.payload().tokenEndpointAuthType());
        assertEquals("http://localhost:8080", captured.payload().origin());
        assertEquals("dyn-1", queryParam(captured.result().authorizationUrl(), "client_id"));
    }

    @Test
    void should_throw_when_start_given_discoveryUnavailable() {
        // given（无法发现 metadata：不发起授权，也不落任何 state）
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL)).thenReturn(Optional.empty());

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.start(
                new StartVaultOAuthCommand("vault_1", MCP_URL, "client-1", "cs-1", ORIGIN)));
        verify(stateStore, never()).save(anyString(), any(), any());
    }

    @Test
    void should_throw_when_start_given_authorizationServerWithoutS256() {
        // given（PKCE S256 是发起授权的必需行为：授权服务器不支持即拒绝）
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL)).thenReturn(Optional.of(new OAuthDiscoveryDTO(
                "https://auth.example.com/authorize", "https://auth.example.com/token", null,
                List.of("plain"), List.of("client_secret_basic"), List.of("read"), MCP_URL)));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.start(
                new StartVaultOAuthCommand("vault_1", MCP_URL, "client-1", "cs-1", ORIGIN)));
        verify(stateStore, never()).save(anyString(), any(), any());
    }

    @Test
    void should_throw_when_start_given_noUsableTokenEndpointAuthMethod() {
        // given（携带密钥但授权服务器只声明 none / 不声明任何方式：无可用的必需行为）
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL)).thenReturn(Optional.of(discovery(null, "none")));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.start(
                new StartVaultOAuthCommand("vault_1", MCP_URL, "client-1", "cs-1", ORIGIN)));
    }

    @Test
    void should_throw_when_start_given_noClientIdAndNoRegistrationEndpoint() {
        // given（未提供 client_id 且授权服务器未声明注册端点）
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL)).thenReturn(Optional.of(discovery(null, "none")));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.start(
                new StartVaultOAuthCommand("vault_1", MCP_URL, null, null, ORIGIN)));
    }

    @Test
    void should_throw_when_start_given_dynamicRegistrationFailed() {
        // given（注册端点存在但未返回 client_id）
        stubVault("vault_1", 1L, false);
        when(discoveryPort.discover(MCP_URL))
                .thenReturn(Optional.of(discovery("https://auth.example.com/register", "none")));
        when(discoveryPort.registerClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.start(
                new StartVaultOAuthCommand("vault_1", MCP_URL, null, null, ORIGIN)));
    }

    @Test
    void should_throwNotFound_when_start_given_vaultOwnedByAnotherUser() {
        // given（owner 隔离：他人保管库视同不存在，且不发起任何出网发现）
        stubVault("vault_1", 9L, false);

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.start(
                new StartVaultOAuthCommand("vault_1", MCP_URL, "client-1", "cs-1", ORIGIN)));
        verify(discoveryPort, never()).discover(anyString());
    }

    @Test
    void should_throwConflict_when_start_given_archivedVault() {
        // given（契约「Vault 非 active → 409」）
        stubVault("vault_1", 1L, true);

        // when & then
        assertThrows(ResourceConflictException.class, () -> service.start(
                new StartVaultOAuthCommand("vault_1", MCP_URL, "client-1", "cs-1", ORIGIN)));
        verify(discoveryPort, never()).discover(anyString());
    }

    // ==================== callback：回调落库 ====================

    @Test
    void should_createOauthCredential_when_callback_given_validStateAndCode() {
        // given（一次性 state 有效 + 换码返回访问令牌 / 刷新令牌 / expires_in）
        stubVault("vault_1", 1L, false);
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));
        when(credentialRepository.existsActiveByVaultIdAndUrl("vault_1", MCP_URL)).thenReturn(false);
        when(tokenExchangePort.exchangeAuthorizationCode(any()))
                .thenReturn(new OAuthTokenOutcomeDTO("at-1", "rt-1", 3600, null));
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        OAuthCallbackResultDTO result = service.callback("code-1", "state-1");

        // then（凭证落在 state 记录的 owner / 保管库 / URL 名下，含到期时间）
        assertEquals("vault_1", result.vaultId());
        assertTrue(result.credentialId().startsWith("vcred_"));
        assertEquals(ORIGIN, result.origin());
        VaultCredential saved = capturedSavedCredential();
        assertEquals(VaultCredentialAuthType.MCP_OAUTH, saved.authType());
        assertEquals(MCP_URL, saved.mcpServerUrl());
        assertNotNull(saved.expiresAt());
        assertTrue(saved.expiresAt().isAfter(now().plusSeconds(3500)));

        // then（轮换后的刷新配置与访问令牌同一次加密信封落库）
        VaultCredentialMaterial material = VaultCredentialMaterialConvert.INSTANCE.parse(
                encryptionUtil.decrypt(saved.ciphertext()));
        assertEquals("at-1", material.accessToken());
        assertEquals("rt-1", material.refresh().refreshToken());
        assertEquals("client-1", material.refresh().clientId());
        assertEquals("https://auth.example.com/token", material.refresh().tokenEndpoint());
        assertEquals(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC,
                material.refresh().tokenEndpointAuth().type());
        assertEquals("cs-1", material.refresh().tokenEndpointAuth().clientSecret());
        assertEquals("read write", material.refresh().scope());
        assertEquals(MCP_URL, material.refresh().resource());
    }

    @Test
    void should_exchangeWithDecryptedSecretAndPkceVerifier_when_callback_given_validState() {
        // given（换码请求必须携带解密后的客户端密钥与 state 记录的 PKCE 校验串、回调地址）
        stubVault("vault_1", 1L, false);
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));
        when(tokenExchangePort.exchangeAuthorizationCode(any()))
                .thenReturn(new OAuthTokenOutcomeDTO("at-1", null, 600, null));
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.callback("code-1", "state-1");

        // then
        ArgumentCaptor<VaultOAuthCodeExchange> captor = ArgumentCaptor.forClass(VaultOAuthCodeExchange.class);
        verify(tokenExchangePort).exchangeAuthorizationCode(captor.capture());
        VaultOAuthCodeExchange exchange = captor.getValue();
        assertEquals("https://auth.example.com/token", exchange.tokenEndpoint());
        assertEquals("client-1", exchange.clientId());
        assertEquals("cs-1", exchange.clientSecret());
        assertEquals(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC, exchange.tokenEndpointAuthType());
        assertEquals("code-1", exchange.code());
        assertEquals("verifier-1", exchange.codeVerifier());
        assertEquals(oauthProperties.getCallbackUrl(), exchange.redirectUri());
        assertEquals(MCP_URL, exchange.resource());
    }

    @Test
    void should_omitRefresh_when_callback_given_exchangeWithoutRefreshToken() {
        // given（授权服务器未下发刷新令牌：凭证无刷新配置，不伪造）
        stubVault("vault_1", 1L, false);
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));
        when(tokenExchangePort.exchangeAuthorizationCode(any()))
                .thenReturn(new OAuthTokenOutcomeDTO("at-1", null, 600, null));
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.callback("code-1", "state-1");

        // then
        VaultCredentialMaterial material = VaultCredentialMaterialConvert.INSTANCE.parse(
                encryptionUtil.decrypt(capturedSavedCredential().ciphertext()));
        assertNull(material.refresh());
    }

    @Test
    void should_omitExpiry_when_callback_given_exchangeWithoutExpiresIn() {
        // given（未声明 expires_in：到期时间为空 = 无到期）
        stubVault("vault_1", 1L, false);
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));
        when(tokenExchangePort.exchangeAuthorizationCode(any()))
                .thenReturn(new OAuthTokenOutcomeDTO("at-1", "rt-1", null, null));
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.callback("code-1", "state-1");

        // then
        assertNull(capturedSavedCredential().expiresAt());
    }

    @Test
    void should_rejectWithoutSideEffects_when_callback_given_replayedOrExpiredState() {
        // given（state 已被消费 / 已过期 / 伪造：读删一体返回空）
        when(stateStore.consume("state-1")).thenReturn(Optional.empty());

        // when & then（不换码、不落库）
        assertThrows(IllegalArgumentException.class, () -> service.callback("code-1", "state-1"));
        verify(tokenExchangePort, never()).exchangeAuthorizationCode(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void should_throw_when_callback_given_blankState() {
        // given（回调缺少 state）
        // when & then（不触碰 state 存储）
        assertThrows(IllegalArgumentException.class, () -> service.callback("code-1", "  "));
        verify(stateStore, never()).consume(anyString());
    }

    @Test
    void should_rejectWithoutSideEffects_when_callback_given_missingCode() {
        // given（state 有效但回调未携带授权码）
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.callback(null, "state-1"));
        verify(tokenExchangePort, never()).exchangeAuthorizationCode(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_callback_given_stateOwnerNotOwningVault() {
        // given（跨 owner：state 记录的 owner 与保管库归属不一致）
        stubVault("vault_1", 9L, false);
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));

        // when & then（不换码、不落库）
        assertThrows(ResourceNotFoundException.class, () -> service.callback("code-1", "state-1"));
        verify(tokenExchangePort, never()).exchangeAuthorizationCode(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void should_throwConflict_when_callback_given_archivedVault() {
        // given（Vault 非 active）
        stubVault("vault_1", 1L, true);
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));

        // when & then
        assertThrows(ResourceConflictException.class, () -> service.callback("code-1", "state-1"));
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void should_throwConflict_when_callback_given_activeCredentialForSameUrl() {
        // given（同一 Vault 下目标 URL 已有 active 凭证：契约 409）
        stubVault("vault_1", 1L, false);
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));
        when(credentialRepository.existsActiveByVaultIdAndUrl("vault_1", MCP_URL)).thenReturn(true);

        // when & then（先裁定再换码，不做无谓出网）
        assertThrows(ResourceConflictException.class, () -> service.callback("code-1", "state-1"));
        verify(tokenExchangePort, never()).exchangeAuthorizationCode(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void should_rejectWithoutPersist_when_callback_given_exchangeReturnedNoAccessToken() {
        // given（换码未换得访问令牌）
        stubVault("vault_1", 1L, false);
        when(stateStore.consume("state-1")).thenReturn(Optional.of(statePayload(ORIGIN)));
        when(tokenExchangePort.exchangeAuthorizationCode(any()))
                .thenReturn(new OAuthTokenOutcomeDTO(null, null, null, null));

        // when & then（不落库，且提示不含响应原文）
        assertThrows(IllegalArgumentException.class, () -> service.callback("code-1", "state-1"));
        verify(credentialRepository, never()).save(any());
    }

    // ==================== 夹具 ====================

    /** 发起授权结果与 state 载荷（同一轮 start 产物）。 */
    private record CapturedStart(String state, VaultOAuthStateDTO payload, OAuthStartResultDTO result) {
    }

    private CapturedStart startAndCaptureState(StartVaultOAuthCommand command) {
        OAuthStartResultDTO result = service.start(command);
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<VaultOAuthStateDTO> payloadCaptor = ArgumentCaptor.forClass(VaultOAuthStateDTO.class);
        verify(stateStore).save(stateCaptor.capture(), payloadCaptor.capture(), any());
        return new CapturedStart(stateCaptor.getValue(), payloadCaptor.getValue(), result);
    }

    /** discovery 报文夹具（S256 + 指定令牌端点鉴权方式集合 + 可选注册端点）。 */
    private OAuthDiscoveryDTO discovery(String registrationEndpoint, String... tokenEndpointAuthMethods) {
        return new OAuthDiscoveryDTO(
                "https://auth.example.com/authorize",
                "https://auth.example.com/token",
                registrationEndpoint,
                List.of("S256"),
                List.of(tokenEndpointAuthMethods),
                List.of("read", "write"),
                MCP_URL);
    }

    /** state 载荷夹具（客户端密钥按生产口径先加密再 Base64，Redis 中无明文）。 */
    private VaultOAuthStateDTO statePayload(String origin) {
        return new VaultOAuthStateDTO(1L, "vault_1", MCP_URL, "client-1",
                Base64.getEncoder().encodeToString(encryptionUtil.encrypt("cs-1")),
                "verifier-1", "https://auth.example.com/token", "client_secret_basic",
                oauthProperties.getCallbackUrl(), MCP_URL, "read write", origin);
    }

    private VaultCredential capturedSavedCredential() {
        ArgumentCaptor<VaultCredential> captor = ArgumentCaptor.forClass(VaultCredential.class);
        verify(credentialRepository).save(captor.capture());
        return captor.getValue();
    }

    private void stubVault(String vaultId, long ownerId, boolean archived) {
        when(vaultRepository.findByVaultId(vaultId))
                .thenReturn(Optional.of(Vault.restore(1L, vaultId, "数据分析库", null, ownerId,
                        archived ? now() : null, now(), now(), "u-1", "u-1")));
    }

    /** 授权地址查询参数取值（URL 解码）。 */
    private String queryParam(String url, String name) {
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (name.equals(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8))) {
                return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** RFC 7636 S256 挑战（测试侧独立计算，与实现互为交叉验证）。 */
    private String s256ChallengeOf(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}