package com.linkroa.deepdataagent.vault.application.service;

import com.linkroa.deepdataagent.vault.application.convert.VaultCredentialMaterialConvert;
import com.linkroa.deepdataagent.vault.application.dto.OAuthRefreshOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthRefreshPort;
import com.linkroa.deepdataagent.vault.application.port.VaultRefreshSingleFlightPort;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialTokenEndpointAuth;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.infrastructure.assembly.DefaultVaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.infrastructure.config.VaultEncryptionProperties;
import com.linkroa.deepdataagent.vault.infrastructure.config.VaultOAuthProperties;
import com.linkroa.deepdataagent.vault.infrastructure.util.VaultCredentialEncryptionUtil;
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

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VaultCredentialRefreshService} 凭证临期刷新编排单测（design D6 / D7）。
 *
 * <p>覆盖：不刷新的三种前提（无 refresh 配置 / 未临期 / 无到期）、临期刷新成功后的
 * 原子回写（访问令牌 + 轮换 refresh token + 新到期时间同批加密落库、CAS 比对行内原始密文）、
 * 刷新失败 / 未换得令牌不覆盖既有令牌、服务商未轮换时沿用原 refresh token、
 * 未声明 {@code expires_in} 时保持既有到期时间、以及两层并发控制（单飞未获取时复用持久化结果、
 * CAS 落败时改读胜出结果、重读异常回落既有材料）。</p>
 *
 * <p>注：application/service 层按 AGENTS.md 不计入单元测试覆盖率，本测试为行为回归保障。</p>
 */
@ExtendWith(MockitoExtension.class)
class VaultCredentialRefreshServiceTest {

    private static final String HOLDER_TOKEN = "holder-1";

    @Mock private VaultOAuthRefreshPort oauthRefreshPort;
    @Mock private VaultRefreshSingleFlightPort singleFlightPort;
    @Mock private VaultCredentialRepository credentialRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private VaultCredentialEncryptionUtil encryptionUtil;
    private VaultCredentialRefreshService service;

    @BeforeEach
    void setUp() {
        VaultEncryptionProperties encryptionProperties = new VaultEncryptionProperties();
        encryptionProperties.setKey("test-vault-key");
        encryptionUtil = new VaultCredentialEncryptionUtil(encryptionProperties);
        VaultCredentialCipherPort cipherPort = new DefaultVaultCredentialCipherPort();
        ReflectionTestUtils.setField(cipherPort, "encryptionUtil", encryptionUtil);
        service = new VaultCredentialRefreshService();
        ReflectionTestUtils.setField(service, "cipherPort", cipherPort);
        ReflectionTestUtils.setField(service, "oauthRefreshPort", oauthRefreshPort);
        ReflectionTestUtils.setField(service, "singleFlightPort", singleFlightPort);
        ReflectionTestUtils.setField(service, "credentialRepository", credentialRepository);
        ReflectionTestUtils.setField(service, "oauthProperties", new VaultOAuthProperties());
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().when(singleFlightPort.tryAcquire(anyString(), any())).thenReturn(Optional.of(HOLDER_TOKEN));
    }

    // ==================== 不刷新的前提 ====================

    @Test
    void should_returnExistingMaterial_when_refreshIfNeeded_given_noRefreshConfigured() {
        // given（无 refresh token 的凭证不可刷新：不占用单飞键、不出网）
        VaultCredential credential = credential(envelopeCiphertext("at-old", null), now().plusSeconds(10));
        VaultCredentialMaterial material = new VaultCredentialMaterial("at-old", null);

        // when
        VaultCredentialMaterial result = service.refreshIfNeeded(credential, material);

        // then
        assertEquals("at-old", result.accessToken());
        verify(singleFlightPort, never()).tryAcquire(anyString(), any());
        verify(oauthRefreshPort, never()).refresh(any());
    }

    @Test
    void should_notRefresh_when_refreshIfNeeded_given_expiryOutsideRefreshWindow() {
        // given（距到期 600s > 刷新窗口 60s：未临期）
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), now().plusSeconds(600));
        VaultCredentialMaterial material = parse(credential.ciphertext());

        // when
        VaultCredentialMaterial result = service.refreshIfNeeded(credential, material);

        // then
        assertEquals("at-old", result.accessToken());
        verify(singleFlightPort, never()).tryAcquire(anyString(), any());
        verify(oauthRefreshPort, never()).refresh(any());
        verify(credentialRepository, never()).updateIfCipherUnchanged(any(), any());
    }

    @Test
    void should_notRefresh_when_refreshIfNeeded_given_noExpiry() {
        // given（到期时间为空 = 无到期，按「不临期」处理）
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), null);
        VaultCredentialMaterial material = parse(credential.ciphertext());

        // when
        VaultCredentialMaterial result = service.refreshIfNeeded(credential, material);

        // then
        assertEquals("at-old", result.accessToken());
        verify(oauthRefreshPort, never()).refresh(any());
    }

    // ==================== 临期刷新成功 ====================

    @Test
    void should_persistRotatedTokensAtomically_when_refreshIfNeeded_given_withinWindowAndRefreshSucceeded() {
        // given（距到期 10s：临期；服务商轮换 refresh token 并返回 expires_in）
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), now().plusSeconds(10));
        VaultCredentialMaterial material = parse(credential.ciphertext());
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO("at-new", "rt-new", 3600, null));
        when(credentialRepository.updateIfCipherUnchanged(any(), any())).thenReturn(true);

        // when
        VaultCredentialMaterial result = service.refreshIfNeeded(credential, material);

        // then（返回值即轮换后材料）
        assertEquals("at-new", result.accessToken());
        assertEquals("rt-new", result.refresh().refreshToken());

        // then（CAS 比对基准是读到的行内原始密文本体——重加密字节必然不同，不可作基准）
        ArgumentCaptor<VaultCredential> updatedCaptor = ArgumentCaptor.forClass(VaultCredential.class);
        ArgumentCaptor<byte[]> expectedCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(credentialRepository).updateIfCipherUnchanged(updatedCaptor.capture(), expectedCaptor.capture());
        assertArrayEquals(credential.ciphertext(), expectedCaptor.getValue());
        VaultCredential updated = updatedCaptor.getValue();
        assertTrue(updated.expiresAt().isAfter(now().plusSeconds(3500)));

        // then（访问令牌与轮换 refresh token 落在同一次加密信封写入）
        VaultCredentialMaterial persisted = parse(updated.ciphertext());
        assertEquals("at-new", persisted.accessToken());
        assertEquals("rt-new", persisted.refresh().refreshToken());
        assertEquals("client-1", persisted.refresh().clientId());
        assertEquals("https://auth.example.com/token", persisted.refresh().tokenEndpoint());
        assertEquals("read write", persisted.refresh().scope());

        // then（持有权释放：值匹配才删）
        verify(singleFlightPort).release("cr_1", HOLDER_TOKEN);
    }

    @Test
    void should_keepRefreshToken_when_refreshIfNeeded_given_providerNotRotating() {
        // given（服务商未轮换 refresh token：沿用原值而非置空）
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), now().plusSeconds(5));
        VaultCredentialMaterial material = parse(credential.ciphertext());
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO("at-new", null, 1800, null));
        when(credentialRepository.updateIfCipherUnchanged(any(), any())).thenReturn(true);

        // when
        service.refreshIfNeeded(credential, material);

        // then
        VaultCredentialMaterial persisted = parse(capturedUpdated().ciphertext());
        assertEquals("at-new", persisted.accessToken());
        assertEquals("rt-old", persisted.refresh().refreshToken());
    }

    @Test
    void should_keepExistingExpiry_when_refreshIfNeeded_given_refreshWithoutExpiresIn() {
        // given（响应未声明 expires_in：保持既有到期时间，不臆造）
        OffsetDateTime expiresAt = now().plusSeconds(5);
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), expiresAt);
        VaultCredentialMaterial material = parse(credential.ciphertext());
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO("at-new", null, null, null));
        when(credentialRepository.updateIfCipherUnchanged(any(), any())).thenReturn(true);

        // when
        service.refreshIfNeeded(credential, material);

        // then
        assertEquals(expiresAt, capturedUpdated().expiresAt());
    }

    // ==================== 刷新失败 / 未胜出 ====================

    @Test
    void should_keepExistingToken_when_refreshIfNeeded_given_refreshReturnedNoAccessToken() {
        // given（未换得令牌：MUST NOT 覆盖已存令牌）
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), now().plusSeconds(5));
        VaultCredentialMaterial material = parse(credential.ciphertext());
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO(null, null, null, null));

        // when
        VaultCredentialMaterial result = service.refreshIfNeeded(credential, material);

        // then
        assertEquals("at-old", result.accessToken());
        verify(credentialRepository, never()).updateIfCipherUnchanged(any(), any());
        verify(singleFlightPort).release("cr_1", HOLDER_TOKEN);
    }

    @Test
    void should_reusePersistedMaterial_when_refreshIfNeeded_given_singleFlightHeldByAnotherInstance() {
        // given（他人持有单飞键：复用其持久化结果，不重复出网以免 refresh token 被轮换作废）
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), now().plusSeconds(5));
        VaultCredentialMaterial material = parse(credential.ciphertext());
        when(singleFlightPort.tryAcquire(anyString(), any())).thenReturn(Optional.empty());
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(credential(envelopeCiphertext("at-fresh", refresh("rt-fresh")),
                        now().plusSeconds(3600))));

        // when
        VaultCredentialMaterial result = service.refreshIfNeeded(credential, material);

        // then
        assertEquals("at-fresh", result.accessToken());
        verify(oauthRefreshPort, never()).refresh(any());
        verify(singleFlightPort, never()).release(anyString(), anyString());
    }

    @Test
    void should_reuseWinnerMaterial_when_refreshIfNeeded_given_casLost() {
        // given（CAS 落败：并发接管者已写入，改读其权威结果，不覆盖）
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), now().plusSeconds(5));
        VaultCredentialMaterial material = parse(credential.ciphertext());
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO("at-mine", "rt-mine", 3600, null));
        when(credentialRepository.updateIfCipherUnchanged(any(), any())).thenReturn(false);
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(credential(envelopeCiphertext("at-winner", refresh("rt-winner")),
                        now().plusSeconds(3600))));

        // when
        VaultCredentialMaterial result = service.refreshIfNeeded(credential, material);

        // then
        assertEquals("at-winner", result.accessToken());
        assertEquals("rt-winner", result.refresh().refreshToken());
        verify(singleFlightPort).release("cr_1", HOLDER_TOKEN);
    }

    @Test
    void should_fallbackToExistingMaterial_when_refreshIfNeeded_given_rereadFailed() {
        // given（CAS 落败且重读异常：绝不因对账失败而丢令牌，回落既有材料）
        VaultCredential credential = credential(envelopeCiphertext("at-old", refresh("rt-old")), now().plusSeconds(5));
        VaultCredentialMaterial material = parse(credential.ciphertext());
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO("at-mine", null, 3600, null));
        when(credentialRepository.updateIfCipherUnchanged(any(), any())).thenReturn(false);
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenThrow(new IllegalStateException("库不可达"));

        // when
        VaultCredentialMaterial result = service.refreshIfNeeded(credential, material);

        // then（返回既有令牌，非空值保证调用方可继续）
        assertEquals("at-old", result.accessToken());
        assertNotNull(result.refresh());
        assertFalse(result.accessToken().isBlank());
    }

    // ==================== 夹具 ====================

    private VaultCredential capturedUpdated() {
        ArgumentCaptor<VaultCredential> captor = ArgumentCaptor.forClass(VaultCredential.class);
        verify(credentialRepository).updateIfCipherUnchanged(captor.capture(), any());
        return captor.getValue();
    }

    private VaultCredential credential(byte[] ciphertext, OffsetDateTime expiresAt) {
        return VaultCredential.restore(1L, "cr_1", "vault_1", VaultCredentialAuthType.MCP_OAUTH,
                "https://mcp.example.com/sse", ciphertext, expiresAt, null, null,
                now(), now(), "u-1", "u-1");
    }

    /** 刷新配置夹具（身份字段固定：刷新路径只换令牌，改写身份即违约定）。 */
    private VaultCredentialRefresh refresh(String refreshToken) {
        return new VaultCredentialRefresh("client-1", refreshToken, "https://auth.example.com/token",
                new VaultCredentialTokenEndpointAuth(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC, "cs-1"),
                "https://mcp.example.com/sse", "read write");
    }

    /** 秘密材料 → 加密信封密文（复用生产转换器，保证测试与实现同形态）。 */
    private byte[] envelopeCiphertext(String accessToken, VaultCredentialRefresh refresh) {
        return encryptionUtil.encrypt(VaultCredentialMaterialConvert.INSTANCE.toEnvelopeJson(
                new VaultCredentialMaterial(accessToken, refresh)));
    }

    /** 密文 → 秘密材料（读回落库形态以断言轮换结果）。 */
    private VaultCredentialMaterial parse(byte[] ciphertext) {
        return VaultCredentialMaterialConvert.INSTANCE.parse(encryptionUtil.decrypt(ciphertext));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}