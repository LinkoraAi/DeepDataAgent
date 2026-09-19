package com.linkroa.deepdataagent.vault.infrastructure.assembly;

import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.application.convert.VaultCredentialMaterialConvert;
import com.linkroa.deepdataagent.vault.application.dto.ResolvedVaultCredentialDTO;
import com.linkroa.deepdataagent.vault.application.service.VaultCredentialRefreshService;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import com.linkroa.deepdataagent.vault.infrastructure.config.VaultEncryptionProperties;
import com.linkroa.deepdataagent.vault.infrastructure.util.VaultCredentialEncryptionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultVaultCredentialResolutionPort} 运行时解密注入端口单测。
 * <p>覆盖 owner 隔离批量加载 + 逐条 AES-GCM 解密 + 单条解密失败跳过不阻断装配 +
 * 归档凭证排除 + 空引用空返回 + {@code mcp_oauth} 刷新接缝（注入恒为当前可用令牌）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultVaultCredentialResolutionPortTest {

    @Mock private VaultRepository vaultRepository;
    @Mock private VaultCredentialRepository credentialRepository;
    @Mock private VaultCredentialRefreshService refreshService;

    private VaultCredentialEncryptionUtil encryptionUtil;
    private DefaultVaultCredentialResolutionPort port;

    @BeforeEach
    void setUp() {
        VaultEncryptionProperties properties = new VaultEncryptionProperties();
        properties.setKey("test-vault-key");
        encryptionUtil = new VaultCredentialEncryptionUtil(properties);
        port = new DefaultVaultCredentialResolutionPort();
        ReflectionTestUtils.setField(port, "vaultRepository", vaultRepository);
        ReflectionTestUtils.setField(port, "credentialRepository", credentialRepository);
        ReflectionTestUtils.setField(port, "encryptionUtil", encryptionUtil);
        ReflectionTestUtils.setField(port, "refreshService", refreshService);
        // 默认透传：未临期 / 无刷新配置的凭证原样注入（按需刷新的专项断言见下方用例）
        lenient().when(refreshService.refreshIfNeeded(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        AuthContext.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void should_returnEmpty_when_resolve_given_nullVaultIds() {
        // when & then（空引用集合不触发任何查询，装配侧无默认挂载）
        assertTrue(port.resolveVaultCredentials(null).isEmpty());
        verify(vaultRepository, never()).findByIds(any(Long.class), anyList());
    }

    @Test
    void should_returnEmpty_when_resolve_given_emptyVaultIds() {
        // when & then
        assertTrue(port.resolveVaultCredentials(List.of()).isEmpty());
        verify(vaultRepository, never()).findByIds(any(Long.class), anyList());
    }

    @Test
    void should_resolvePlaintexts_when_resolve_given_ownedVaultsWithCredentials() {
        // given（当前 owner 的未归档保管库 + 其下两条凭证，密文为真实 AES-GCM 载荷）
        Vault vault = vaultRow("vault_1", 1L, false);
        Vault vault2 = vaultRow("vault_2", 1L, false);
        when(vaultRepository.findByIds(1L, List.of("vault_1", "vault_2")))
                .thenReturn(List.of(vault, vault2));
        VaultCredential c1 = credentialRow("cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                "https://mcp-one.example.com", encryptionUtil.encrypt("sk-one"));
        VaultCredential c2 = credentialRow("cr_2", "vault_2", VaultCredentialAuthType.MCP_OAUTH,
                "https://mcp-two.example.com", encryptionUtil.encrypt("sk-two"));
        when(credentialRepository.findByVaultIds(anyList())).thenReturn(List.of(c1, c2));

        // when
        List<ResolvedVaultCredentialDTO> resolved = port.resolveVaultCredentials(List.of("vault_1", "vault_2"));

        // then（批量解密注入，元数据 + 明文一一映射）
        assertEquals(2, resolved.size());
        ResolvedVaultCredentialDTO first = resolved.get(0);
        assertEquals("vault_1", first.vaultId());
        assertEquals("cr_1", first.credentialId());
        assertEquals("static_bearer", first.authType());
        assertEquals("https://mcp-one.example.com", first.mcpServerUrl());
        assertEquals("sk-one", first.token());
        assertEquals("mcp_oauth", resolved.get(1).authType());
        assertEquals("sk-two", resolved.get(1).token());
    }

    @Test
    void should_skipArchived_when_resolve_given_archivedCredential() {
        // given（同库下一条已归档凭证：归档不再用于新 Session 挂载注入，解密与注入均应排除）
        Vault vault = vaultRow("vault_1", 1L, false);
        when(vaultRepository.findByIds(1L, List.of("vault_1"))).thenReturn(List.of(vault));
        VaultCredential active = credentialRow("cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                "https://mcp-one.example.com", encryptionUtil.encrypt("sk-active"));
        VaultCredential archived = archivedCredentialRow("cr_2", "vault_1", encryptionUtil.encrypt("sk-archived"));
        when(credentialRepository.findByVaultIds(anyList())).thenReturn(List.of(active, archived));

        // when
        List<ResolvedVaultCredentialDTO> resolved = port.resolveVaultCredentials(List.of("vault_1"));

        // then（归档凭证被排除，未归档凭证正常注入）
        assertEquals(1, resolved.size());
        assertEquals("cr_1", resolved.get(0).credentialId());
    }

    @Test
    void should_skipFailedDecrypt_when_resolve_given_corruptedCiphertext() {
        // given（一条密文被篡改导致解密失败：单条失败不阻断其他凭证注入）
        Vault vault = vaultRow("vault_1", 1L, false);
        when(vaultRepository.findByIds(1L, List.of("vault_1"))).thenReturn(List.of(vault));
        VaultCredential ok = credentialRow("cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                "https://mcp-one.example.com", encryptionUtil.encrypt("sk-ok"));
        VaultCredential corrupt = credentialRow("cr_2", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                "https://mcp-two.example.com", "corrupted-ciphertext".getBytes(StandardCharsets.UTF_8));
        when(credentialRepository.findByVaultIds(anyList())).thenReturn(List.of(ok, corrupt));

        // when
        List<ResolvedVaultCredentialDTO> resolved = port.resolveVaultCredentials(List.of("vault_1"));

        // then（坏凭证被跳过，好凭证正常注入）
        assertEquals(1, resolved.size());
        assertEquals("cr_1", resolved.get(0).credentialId());
    }

    @Test
    void should_injectRefreshedToken_when_resolve_given_oauthCredentialWithinRefreshWindow() {
        // given（mcp_oauth 信封 + 刷新接缝换得新令牌：注入的必须是刷新后令牌而非信封原文）
        Vault vault = vaultRow("vault_1", 1L, false);
        when(vaultRepository.findByIds(1L, List.of("vault_1"))).thenReturn(List.of(vault));
        VaultCredential credential = credentialRow("cr_1", "vault_1", VaultCredentialAuthType.MCP_OAUTH,
                "https://mcp-one.example.com", envelopeCiphertext("at-old"));
        when(credentialRepository.findByVaultIds(anyList())).thenReturn(List.of(credential));
        when(refreshService.refreshIfNeeded(any(), any()))
                .thenReturn(new VaultCredentialMaterial("at-new", null));

        // when
        List<ResolvedVaultCredentialDTO> resolved = port.resolveVaultCredentials(List.of("vault_1"));

        // then（注入的是当前可用访问令牌，不是加密信封）
        assertEquals(1, resolved.size());
        assertEquals("at-new", resolved.get(0).token());
    }

    @Test
    void should_skipCredential_when_resolve_given_refreshSeamThrows() {
        // given（刷新接缝抛异常：单条凭证跳过注入，不阻断装配链路）
        Vault vault = vaultRow("vault_1", 1L, false);
        when(vaultRepository.findByIds(1L, List.of("vault_1"))).thenReturn(List.of(vault));
        VaultCredential credential = credentialRow("cr_1", "vault_1", VaultCredentialAuthType.MCP_OAUTH,
                "https://mcp-one.example.com", envelopeCiphertext("at-old"));
        when(credentialRepository.findByVaultIds(anyList())).thenReturn(List.of(credential));
        when(refreshService.refreshIfNeeded(any(), any())).thenThrow(new IllegalStateException("刷新不可用"));

        // when & then
        assertTrue(port.resolveVaultCredentials(List.of("vault_1")).isEmpty());
    }

    @Test
    void should_resolveWithExplicitOwner_when_resolveVaultCredentials_given_ownerIdOverloadWithoutAuthContext() {
        // given（异步 / 调度链路：无认证线程上下文，owner 显式传参且不回退 ThreadLocal）
        AuthContext.clear();
        Vault vault = vaultRow("vault_1", 9L, false);
        when(vaultRepository.findByIds(9L, List.of("vault_1"))).thenReturn(List.of(vault));
        VaultCredential c1 = credentialRow("cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                "https://mcp-one.example.com", encryptionUtil.encrypt("sk-owner9"));
        when(credentialRepository.findByVaultIds(anyList())).thenReturn(List.of(c1));

        // when
        List<ResolvedVaultCredentialDTO> resolved = port.resolveVaultCredentials(9L, List.of("vault_1"));

        // then（按显式 owner 完成 owner 隔离加载与解密注入）
        assertEquals(1, resolved.size());
        assertEquals("sk-owner9", resolved.get(0).token());
        verify(vaultRepository).findByIds(9L, List.of("vault_1"));
    }

    @Test
    void should_returnEmpty_when_resolveVaultCredentials_given_ownerIdOverloadWithNullOwnerOrBlankIds() {
        // when & then（缺参短路，不触碰仓储）
        assertTrue(port.resolveVaultCredentials(null, List.of("vault_1")).isEmpty());
        assertTrue(port.resolveVaultCredentials(1L, null).isEmpty());
        assertTrue(port.resolveVaultCredentials(1L, List.of()).isEmpty());
        verify(vaultRepository, never()).findByIds(any(Long.class), anyList());
    }

    @Test
    void should_returnEmpty_when_resolve_given_ownedVaultsFilteredOut() {
        // given（owner 隔离：他人 / 已归档保管库不进数据面）
        when(vaultRepository.findByIds(1L, List.of("vault_x"))).thenReturn(List.of());

        // when
        List<ResolvedVaultCredentialDTO> resolved = port.resolveVaultCredentials(List.of("vault_x"));

        // then
        assertTrue(resolved.isEmpty());
        verify(credentialRepository, never()).findByVaultIds(anyList());
    }

    private Vault vaultRow(String vaultId, Long ownerId, boolean archived) {
        OffsetDateTime archivedAt = archived ? now() : null;
        return Vault.restore(1L, vaultId, "数据分析库", null, ownerId, archivedAt,
                now(), now(), "u-1", "u-1");
    }

    private VaultCredential credentialRow(String credentialId, String vaultId, VaultCredentialAuthType authType,
                                         String mcpServerUrl, byte[] ciphertext) {
        return VaultCredential.restore(1L, credentialId, vaultId, authType, mcpServerUrl, ciphertext,
                null, null, null, now(), now(), "u-1", "u-1");
    }

    private VaultCredential archivedCredentialRow(String credentialId, String vaultId, byte[] ciphertext) {
        return VaultCredential.restore(1L, credentialId, vaultId, VaultCredentialAuthType.STATIC_BEARER,
                "https://mcp-archived.example.com", ciphertext, null, null, now(), now(), now(), "u-1", "u-1");
    }

    /** mcp_oauth 秘密材料信封密文（design D5 整包形态，与生产转换器同口径）。 */
    private byte[] envelopeCiphertext(String accessToken) {
        return encryptionUtil.encrypt(VaultCredentialMaterialConvert.INSTANCE.toEnvelopeJson(
                new VaultCredentialMaterial(accessToken, null)));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}
