package com.linkroa.deepdataagent.vault.application.service;

import com.linkroa.deepdataagent.runtime.api.SessionReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.TooManyRequestsException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.application.command.AddVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.command.UpdateVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.convert.VaultCredentialMaterialConvert;
import com.linkroa.deepdataagent.vault.application.dto.HttpDiagnosticDTO;
import com.linkroa.deepdataagent.vault.application.dto.McpProbeOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthRefreshOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.port.McpInitializeProbePort;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthRefreshPort;
import com.linkroa.deepdataagent.vault.application.query.ListVaultCredentialQuery;
import com.linkroa.deepdataagent.vault.application.query.ListVaultQuery;
import com.linkroa.deepdataagent.vault.application.service.VaultApplicationService.CredentialValidationResult;
import com.linkroa.deepdataagent.vault.application.service.VaultApplicationService.VaultSearchResult;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialListFilter;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialTokenEndpointAuth;
import com.linkroa.deepdataagent.vault.domain.model.VaultListFilter;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialRefreshStatus;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialTokenEndpointAuthType;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialValidationStatus;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import com.linkroa.deepdataagent.vault.infrastructure.assembly.DefaultVaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.infrastructure.config.VaultEncryptionProperties;
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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VaultApplicationService} 保管库应用服务单测。
 * <p>覆盖：validateCredential 校验语义（非 http(s) skipped / 解密失败 failed /
 * SSRF 信任边界拒绝 / 已归档 409 / 明文不出边界）、游标列表（含归档锚点定位、before 方向翻转）、
 * 删除引用守卫（409）与逻辑删级联、凭证归档幂等、
 * 凭证更新 merge 补丁（按类型轮换 / 未传保持原值 / 显式 null 清除 / 元数据键级删除与整体重置 /
 * 类型不一致 400 / 无 refresh 配置补加 400 / 归档 409 / 旧明文形态升级为加密信封）、
 * addCredential 保管库级约束（同 MCP URL 冲突 409 / 活跃凭证数上限 409）。</p>
 *
 * <p>注：application/service 层按 AGENTS.md 不计入单元测试覆盖率，本测试为行为回归保障。</p>
 */
@ExtendWith(MockitoExtension.class)
class VaultApplicationServiceTest {

    @Mock private VaultRepository vaultRepository;
    @Mock private VaultCredentialRepository credentialRepository;
    @Mock private VaultOAuthRefreshPort oauthRefreshPort;
    @Mock private McpInitializeProbePort mcpInitializeProbePort;
    @Mock private SessionReferenceApi sessionReferenceApi;
    @Mock private TransactionTemplate transactionTemplate;

    private VaultCredentialEncryptionUtil encryptionUtil;
    private VaultApplicationService service;

    @BeforeEach
    void setUp() {
        VaultEncryptionProperties properties = new VaultEncryptionProperties();
        properties.setKey("test-vault-key");
        encryptionUtil = new VaultCredentialEncryptionUtil(properties);
        VaultCredentialCipherPort cipherPort = new DefaultVaultCredentialCipherPort();
        ReflectionTestUtils.setField(cipherPort, "encryptionUtil", encryptionUtil);
        service = new VaultApplicationService();
        ReflectionTestUtils.setField(service, "vaultRepository", vaultRepository);
        ReflectionTestUtils.setField(service, "credentialRepository", credentialRepository);
        ReflectionTestUtils.setField(service, "cipherPort", cipherPort);
        ReflectionTestUtils.setField(service, "oauthRefreshPort", oauthRefreshPort);
        ReflectionTestUtils.setField(service, "mcpInitializeProbePort", mcpInitializeProbePort);
        ReflectionTestUtils.setField(service, "sessionReferenceApi", sessionReferenceApi);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        AuthContext.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    // ==================== validateCredential（design D4：mcp_oauth 专属 + 先刷新后探测） ====================

    @Test
    void should_throwConflict_when_validate_given_staticBearerCredential() {
        // given（validate 收敛为 active mcp_oauth 专属：static_bearer → 409，且不发起任何探测）
        stubVaultOwnedActive();
        VaultCredential credential = credential("https://mcp.example.com/sse", encryptionUtil.encrypt("sk-plain"));
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1")).thenReturn(Optional.of(credential));

        // when & then
        assertThrows(ResourceConflictException.class, () -> service.validateCredential("vault_1", "cr_1"));
        verify(mcpInitializeProbePort, never()).probe(anyString(), anyString());
        verify(oauthRefreshPort, never()).refresh(any());
    }

    @Test
    void should_returnInvalidWithoutProbe_when_validate_given_corruptCiphertext() {
        // given（无可信访问令牌：解密失败 → invalid，且不发起刷新 / 探测，不泄露明文）
        stubVaultOwnedActive();
        VaultCredential credential = oauthCredential(
                "corrupted-ciphertext".getBytes(StandardCharsets.UTF_8), null, null);
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1")).thenReturn(Optional.of(credential));

        // when
        CredentialValidationResult result = service.validateCredential("vault_1", "cr_1");

        // then
        assertEquals(VaultCredentialValidationStatus.INVALID, result.status());
        assertFalse(result.hasRefreshToken());
        assertEquals(VaultCredentialRefreshStatus.NO_REFRESH_TOKEN, result.refresh().status());
        assertNull(result.mcpProbe());
        assertEquals("cr_1", result.credentialId());
        assertEquals("vault_1", result.vaultId());
        verify(mcpInitializeProbePort, never()).probe(anyString(), anyString());
        verify(oauthRefreshPort, never()).refresh(any());
    }

    @Test
    void should_reportNoRefreshToken_when_validate_given_credentialWithoutRefreshConfig() {
        // given（无刷新配置：跳过刷新（no_refresh_token），直接以既有访问令牌探测且通过）
        stubVaultOwnedActive();
        stubActiveOauthCredential(envelopeCiphertext("at-1", null), null);
        stubProbe(200);

        // when
        CredentialValidationResult result = service.validateCredential("vault_1", "cr_1");

        // then（探测成功 → mcp_probe 不出现）
        assertEquals(VaultCredentialValidationStatus.VALID, result.status());
        assertFalse(result.hasRefreshToken());
        assertEquals(VaultCredentialRefreshStatus.NO_REFRESH_TOKEN, result.refresh().status());
        assertNull(result.mcpProbe());
        verify(oauthRefreshPort, never()).refresh(any());
        verify(mcpInitializeProbePort).probe("https://mcp.example.com/sse", "at-1");
    }

    @Test
    void should_persistRotatedTokenAndProbeWithIt_when_validate_given_refreshSucceeded() {
        // given（刷新成功：轮换后的令牌 MUST 落库，且探测 MUST 以新访问令牌发起）
        stubVaultOwnedActive();
        stubActiveOauthCredential(envelopeCiphertext("at-old", refresh("rt-old")), null);
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO(
                "at-new", "rt-new", 3600, httpResponse(200, "application/json", "{\"access_token\":\"at-new\"}")));
        stubProbe(200);

        // when
        CredentialValidationResult result = service.validateCredential("vault_1", "cr_1");

        // then（结论 valid；刷新 succeeded；持久化后的信封承载轮换后的访问令牌与刷新令牌）
        assertEquals(VaultCredentialValidationStatus.VALID, result.status());
        assertEquals(VaultCredentialRefreshStatus.SUCCEEDED, result.refresh().status());
        assertTrue(result.hasRefreshToken());
        ArgumentCaptor<VaultCredential> captor = ArgumentCaptor.forClass(VaultCredential.class);
        verify(credentialRepository).update(captor.capture());
        VaultCredentialMaterial rotated = VaultCredentialMaterialConvert.INSTANCE.parse(
                encryptionUtil.decrypt(captor.getValue().ciphertext()));
        assertEquals("at-new", rotated.accessToken());
        assertEquals("rt-new", rotated.refresh().refreshToken());
        assertNotNull(captor.getValue().expiresAt());
        assertTrue(captor.getValue().expiresAt().isAfter(
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(3590)));
        verify(mcpInitializeProbePort).probe("https://mcp.example.com/sse", "at-new");
    }

    @Test
    void should_keepStoredToken_when_validate_given_refreshDeterministicallyRejected() {
        // given（令牌端点确定性 4xx：refresh=failed 且含脱敏响应诊断；刷新失败不覆盖已存令牌）
        stubVaultOwnedActive();
        stubActiveOauthCredential(envelopeCiphertext("at-1", refresh("rt-1")), null);
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO(
                null, null, null, httpResponse(400, "application/json", "{\"error\":\"invalid_grant\"}")));
        stubProbe(200);

        // when
        CredentialValidationResult result = service.validateCredential("vault_1", "cr_1");

        // then
        assertEquals(VaultCredentialRefreshStatus.FAILED, result.refresh().status());
        assertEquals(400, result.refresh().httpResponse().statusCode());
        assertEquals("{\"error\":\"invalid_grant\"}", result.refresh().httpResponse().body());
        verify(credentialRepository, never()).update(any());
        verify(mcpInitializeProbePort).probe("https://mcp.example.com/sse", "at-1");
    }

    @Test
    void should_reportConnectError_when_validate_given_refreshUnreachable() {
        // given（刷新请求未收到响应：refresh=connect_error，无响应诊断，不落更新）
        stubVaultOwnedActive();
        stubActiveOauthCredential(envelopeCiphertext("at-1", refresh("rt-1")), null);
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO(null, null, null, null));
        stubProbeUnresponsive();

        // when
        CredentialValidationResult result = service.validateCredential("vault_1", "cr_1");

        // then
        assertEquals(VaultCredentialRefreshStatus.CONNECT_ERROR, result.refresh().status());
        assertNull(result.refresh().httpResponse());
        verify(credentialRepository, never()).update(any());
    }

    @Test
    void should_reportConnectError_when_validate_given_refreshServerError() {
        // given（令牌端点 5xx：不可归因于凭证 → connect_error（不得判 failed 误导重新授权））
        stubVaultOwnedActive();
        stubActiveOauthCredential(envelopeCiphertext("at-1", refresh("rt-1")), null);
        when(oauthRefreshPort.refresh(any())).thenReturn(new OAuthRefreshOutcomeDTO(
                null, null, null, httpResponse(503, "text/plain", "service unavailable")));
        stubProbe(200);

        // when
        CredentialValidationResult result = service.validateCredential("vault_1", "cr_1");

        // then
        assertEquals(VaultCredentialRefreshStatus.CONNECT_ERROR, result.refresh().status());
        assertEquals(503, result.refresh().httpResponse().statusCode());
        verify(credentialRepository, never()).update(any());
    }

    @Test
    void should_returnInvalidWithProbeDiagnostic_when_validate_given_probeDeterministicallyRejected() {
        // given（MCP 握手被确定性拒绝（401）：invalid，mcp_probe 出现且含失败的调用名与响应诊断）
        stubVaultOwnedActive();
        stubActiveOauthCredential(envelopeCiphertext("at-1", null), null);
        stubProbe(401);

        // when
        CredentialValidationResult result = service.validateCredential("vault_1", "cr_1");

        // then
        assertEquals(VaultCredentialValidationStatus.INVALID, result.status());
        assertEquals("initialize", result.mcpProbe().method());
        assertEquals(401, result.mcpProbe().httpResponse().statusCode());
    }

    @Test
    void should_classifyProbeStatus_when_validate_given_boundaryStatusCodes() {
        // given（分类规则边界：408/429 与 5xx 不可据以判定失效 → unknown；其余 4xx → invalid；3xx → valid）
        stubVaultOwnedActive();
        stubActiveOauthCredential(envelopeCiphertext("at-1", null), null);

        // when & then
        stubProbe(408);
        assertEquals(VaultCredentialValidationStatus.UNKNOWN, service.validateCredential("vault_1", "cr_1").status());
        stubProbe(429);
        assertEquals(VaultCredentialValidationStatus.UNKNOWN, service.validateCredential("vault_1", "cr_1").status());
        stubProbe(500);
        assertEquals(VaultCredentialValidationStatus.UNKNOWN, service.validateCredential("vault_1", "cr_1").status());
        stubProbe(404);
        assertEquals(VaultCredentialValidationStatus.INVALID, service.validateCredential("vault_1", "cr_1").status());
        stubProbe(302);
        assertEquals(VaultCredentialValidationStatus.VALID, service.validateCredential("vault_1", "cr_1").status());
    }

    @Test
    void should_returnUnknownWithProbeDiagnostic_when_validate_given_probeUnreachable() {
        // given（探测未收到响应：超出信任边界 / 连接错误 → unknown，mcp_probe 无响应诊断）
        stubVaultOwnedActive();
        stubActiveOauthCredential(envelopeCiphertext("at-1", null), null);
        stubProbeUnresponsive();

        // when
        CredentialValidationResult result = service.validateCredential("vault_1", "cr_1");

        // then
        assertEquals(VaultCredentialValidationStatus.UNKNOWN, result.status());
        assertEquals("initialize", result.mcpProbe().method());
        assertNull(result.mcpProbe().httpResponse());
    }

    @Test
    void should_throw_when_validate_given_credentialNotFound() {
        // given（凭证不存在 → 404，不区分凭证是否归属）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_x")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.validateCredential("vault_1", "cr_x"));
    }

    @Test
    void should_throw_when_validate_given_vaultNotOwned() {
        // given（他人保管库 → 404，不泄露存在性）
        Vault othersVault = vaultRow(2L);
        when(vaultRepository.findByVaultId("vault_1")).thenReturn(Optional.of(othersVault));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.validateCredential("vault_1", "cr_1"));
    }

    @Test
    void should_throwConflict_when_validate_given_archivedCredential() {
        // given（6.6：已归档凭证不参与校验 → 409）
        stubVaultOwnedActive();
        VaultCredential archived = archivedCredential("https://api.example.com/v1");
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1")).thenReturn(Optional.of(archived));

        // when & then
        assertThrows(ResourceConflictException.class, () -> service.validateCredential("vault_1", "cr_1"));
    }

    // ==================== list（6.6 游标化） ====================

    @Test
    void should_trimProbeRow_when_list_given_hasMorePage() {
        // given（探针多取一行：返回 limit+1 行 → 裁切至 limit 且 has_more=true）
        List<Vault> rows = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            rows.add(buildVault(i));
        }
        when(vaultRepository.findByCursor(eq(1L), any(VaultListFilter.class), eq(21))).thenReturn(rows);
        ListVaultQuery query = new ListVaultQuery(1L, null, null, Boolean.FALSE,
                CursorPageParams.parse(null, null, null));

        // when
        CursorPage<Vault> page = service.list(query);

        // then
        assertEquals(20, page.data().size());
        assertTrue(page.hasMore());
        assertEquals("vault_1", page.firstId());
        assertEquals("vault_20", page.lastId());
    }

    @Test
    void should_allowArchivedAnchor_when_list_given_afterIdPointingArchived() {
        // given（游标锚点允许指向已归档行：status=archived 翻页场景）
        Vault anchor = Vault.restore(7L, "vault_a", "归档库", null, 1L,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), "u-1", "u-1");
        when(vaultRepository.findByVaultId("vault_a")).thenReturn(Optional.of(anchor));
        when(vaultRepository.findByCursor(eq(1L), any(VaultListFilter.class), eq(21))).thenReturn(List.of());
        ListVaultQuery query = new ListVaultQuery(1L, null, null, Boolean.TRUE,
                CursorPageParams.parse(null, "vault_a", null));

        // when
        CursorPage<Vault> page = service.list(query);

        // then（锚点行位点成对装配进过滤器，方向为默认降序）
        ArgumentCaptor<VaultListFilter> captor = ArgumentCaptor.forClass(VaultListFilter.class);
        verify(vaultRepository).findByCursor(eq(1L), captor.capture(), eq(21));
        VaultListFilter filter = captor.getValue();
        assertEquals(anchor.createdAt(), filter.cursorCreatedAt());
        assertEquals(7L, filter.cursorRowId());
        assertFalse(filter.reverse());
        assertEquals(Boolean.TRUE, filter.archived());
        assertFalse(page.hasMore());
    }

    @Test
    void should_flipRows_when_list_given_beforeId() {
        // given（before 方向：升序读取后翻转回降序）
        Vault anchor = buildVault(2);
        when(vaultRepository.findByVaultId("vault_2")).thenReturn(Optional.of(anchor));
        when(vaultRepository.findByCursor(eq(1L), any(VaultListFilter.class), eq(3)))
                .thenReturn(List.of(buildVault(1), buildVault(2), buildVault(3)));
        ListVaultQuery query = new ListVaultQuery(1L, null, null, Boolean.FALSE,
                CursorPageParams.parse("2", null, "vault_2"));

        // when
        CursorPage<Vault> page = service.list(query);

        // then（探针 3 行 → 裁 2 行并翻转：vault_2 在首、vault_1 在尾）
        assertTrue(page.hasMore());
        assertEquals("vault_2", page.firstId());
        assertEquals("vault_1", page.lastId());
        ArgumentCaptor<VaultListFilter> captor = ArgumentCaptor.forClass(VaultListFilter.class);
        verify(vaultRepository).findByCursor(eq(1L), captor.capture(), eq(3));
        assertTrue(captor.getValue().reverse());
    }

    @Test
    void should_throwNotFound_when_list_given_anchorNotOwned() {
        // given（游标锚点越权 → 404，不泄露存在性）
        when(vaultRepository.findByVaultId("vault_x")).thenReturn(Optional.of(vaultRow(2L)));
        ListVaultQuery query = new ListVaultQuery(1L, null, null, null,
                CursorPageParams.parse(null, "vault_x", null));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.list(query));
    }

    // ==================== delete（6.6 引用守卫 + 逻辑删） ====================

    @Test
    void should_throwConflict_when_delete_given_stillReferenced() {
        // given（仍有历史 Session 引用 → 409，拒绝删除）
        when(vaultRepository.findByVaultIdForUpdate("vault_1")).thenReturn(Optional.of(vaultRow(1L)));
        when(sessionReferenceApi.countSessionsByVaultId("vault_1")).thenReturn(2L);

        // when & then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.delete("vault_1"));
        assertTrue(ex.getMessage().contains("2"));
        verify(vaultRepository, never()).deleteByVaultId(anyString());
        verify(credentialRepository, never()).deleteByVaultId(anyString());
    }

    @Test
    void should_logicalDeleteCascade_when_delete_given_noReference() {
        // given（无引用：级联逻辑删凭证后逻辑删保管库）
        when(vaultRepository.findByVaultIdForUpdate("vault_1")).thenReturn(Optional.of(vaultRow(1L)));
        when(sessionReferenceApi.countSessionsByVaultId("vault_1")).thenReturn(0L);

        // when
        service.delete("vault_1");

        // then
        verify(credentialRepository).deleteByVaultId("vault_1");
        verify(vaultRepository).deleteByVaultId("vault_1");
    }

    // ==================== archiveCredential / updateCredential（6.6 新增） ====================

    @Test
    void should_archiveCredential_when_archive_given_activeCredential() {
        // given（未归档凭证：置 archived_at）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(credential("https://mcp.example.com/sse", encryptionUtil.encrypt("sk-plain"))));

        // when
        service.archiveCredential("vault_1", "cr_1");

        // then
        verify(credentialRepository).archive(eq("vault_1"), eq("cr_1"), any(OffsetDateTime.class));
    }

    @Test
    void should_throwConflict_when_archiveCredential_given_alreadyArchived() {
        // given（已归档凭证重复归档 → 409，不重复置位）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(archivedCredential("https://mcp.example.com/sse")));

        // when & then
        assertThrows(ResourceConflictException.class, () -> service.archiveCredential("vault_1", "cr_1"));
        verify(credentialRepository, never()).archive(anyString(), anyString(), any());
    }

    @Test
    void should_throwNotFound_when_archiveCredential_given_credentialMissing() {
        // given（凭证不存在 → 404）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_x")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.archiveCredential("vault_1", "cr_x"));
    }

    // ==================== updateCredential（merge 补丁，design D3 / D3.1） ====================

    @Test
    void should_rotateCiphertext_when_updateCredential_given_staticBearerTokenOnly() {
        // given（仅轮换 token：密文替换为新明文的密文，身份字段保持）
        stubVaultOwnedActive();
        byte[] oldCiphertext = encryptionUtil.encrypt("sk-old");
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(credential("https://mcp.example.com/sse", oldCiphertext)));
        when(credentialRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential updated = service.updateCredential(tokenCommand("sk-new"));

        // then（新密文可解回新明文，绑定 URL 与鉴权类型原样保持）
        assertEquals("sk-new", encryptionUtil.decrypt(updated.ciphertext()));
        assertEquals("https://mcp.example.com/sse", updated.mcpServerUrl());
        assertEquals(VaultCredentialAuthType.STATIC_BEARER, updated.authType());
    }

    @Test
    void should_keepMaterials_when_updateCredential_given_metadataOnly() {
        // given（未传的秘密材料字段 = 不改：密文与到期时间保持，仅元数据浅合并）
        stubVaultOwnedActive();
        byte[] oldCiphertext = encryptionUtil.encrypt("sk-old");
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(1);
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(VaultCredential.restore(1L, "cr_1", "vault_1",
                        VaultCredentialAuthType.MCP_OAUTH, "https://mcp.example.com/sse", oldCiphertext,
                        expiresAt, "{\"a\":1}", null,
                        OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                        OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), "u-1", "u-1")));
        when(credentialRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential updated = service.updateCredential(metadataCommand("{\"env\":\"prod\"}"));

        // then
        assertArrayEquals(oldCiphertext, updated.ciphertext());
        assertEquals(expiresAt, updated.expiresAt());
        assertEquals("{\"a\":1,\"env\":\"prod\"}", updated.metadata());
    }

    @Test
    void should_deleteMetadataKey_when_updateCredential_given_metadataKeyExplicitNull() {
        // given（metadata 单 key 传 null = 删除该键，其余键保留）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(oauthCredential(envelopeCiphertext("at-1", null), null, "{\"a\":1,\"b\":2}")));
        when(credentialRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential updated = service.updateCredential(metadataCommand("{\"a\":null}"));

        // then
        assertEquals("{\"b\":2}", updated.metadata());
    }

    @Test
    void should_resetMetadata_when_updateCredential_given_metadataExplicitNull() {
        // given（metadata 整体传 null = 重置为 {}）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(oauthCredential(envelopeCiphertext("at-1", null), null, "{\"a\":1}")));
        when(credentialRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential updated = service.updateCredential(metadataCommand(null));

        // then
        assertEquals("{}", updated.metadata());
    }

    @Test
    void should_clearExpiresAt_when_updateCredential_given_expiresAtExplicitNull() {
        // given（mcp_oauth 显式提交 expires_at=null = 清除到期时间）
        stubVaultOwnedActive();
        byte[] ciphertext = envelopeCiphertext("at-1", null);
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(oauthCredential(ciphertext,
                        OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(1), null)));
        when(credentialRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UpdateVaultCredentialCommand command = new UpdateVaultCredentialCommand(
                "vault_1", "cr_1", "mcp_oauth",
                null, false, null, false, null, true,
                null, false, null, false, null, false, null, false);

        // when
        VaultCredential updated = service.updateCredential(command);

        // then
        assertNull(updated.expiresAt());
    }

    @Test
    void should_rotateAccessToken_when_updateCredential_given_oauthAccessTokenOnly() {
        // given（mcp_oauth 轮换 access_token：未传的 expires_at 保持，刷新配置原样保留）
        stubVaultOwnedActive();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(1);
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(oauthCredential(envelopeCiphertext("at-old", refresh("rt-1")),
                        expiresAt, null)));
        when(credentialRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential updated = service.updateCredential(accessTokenCommand("at-new"));

        // then（信封内访问令牌替换、刷新配置完整保留）
        VaultCredentialMaterial material = VaultCredentialMaterialConvert.INSTANCE.parse(
                encryptionUtil.decrypt(updated.ciphertext()));
        assertEquals("at-new", material.accessToken());
        assertEquals("rt-1", material.refresh().refreshToken());
        assertEquals(expiresAt, updated.expiresAt());
    }

    @Test
    void should_patchRefreshToken_when_updateCredential_given_refreshTokenOnly() {
        // given（refresh 仅可修补：刷新令牌替换，访问令牌与刷新身份字段保持）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(oauthCredential(envelopeCiphertext("at-1", refresh("rt-old")), null, null)));
        when(credentialRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential updated = service.updateCredential(refreshTokenCommand("rt-new"));

        // then
        VaultCredentialMaterial material = VaultCredentialMaterialConvert.INSTANCE.parse(
                encryptionUtil.decrypt(updated.ciphertext()));
        assertEquals("at-1", material.accessToken());
        assertEquals("rt-new", material.refresh().refreshToken());
        assertEquals("client-1", material.refresh().clientId());
        assertEquals("https://auth.example.com/token", material.refresh().tokenEndpoint());
    }

    @Test
    void should_upgradeLegacyPlaintext_when_updateCredential_given_legacyBareTokenAndAccessToken() {
        // given（旧形态兼容：解密明文是访问令牌裸串而非信封；轮换后升级为信封形态）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(oauthCredential(encryptionUtil.encrypt("at-legacy"), null, null)));
        when(credentialRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential updated = service.updateCredential(accessTokenCommand("at-new"));

        // then（信封可解析且刷新配置仍为空）
        VaultCredentialMaterial material = VaultCredentialMaterialConvert.INSTANCE.parse(
                encryptionUtil.decrypt(updated.ciphertext()));
        assertEquals("at-new", material.accessToken());
        assertFalse(material.hasRefresh());
    }

    @Test
    void should_throwBadRequest_when_updateCredential_given_authTypeMismatch() {
        // given（auth.type 与既有类型不一致 → 400，且不落更新）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(credential("https://mcp.example.com/sse", encryptionUtil.encrypt("sk-old"))));

        // when & then（既有 static_bearer 凭证被声明为 mcp_oauth）
        assertThrows(IllegalArgumentException.class, () -> service.updateCredential(accessTokenCommand("at-1")));
        verify(credentialRepository, never()).update(any());
    }

    @Test
    void should_throwBadRequest_when_updateCredential_given_addRefreshToCredentialWithoutRefresh() {
        // given（创建时无 refresh 配置，补加 refresh → 400：不造出刷新路径必然失败的半残凭证）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(oauthCredential(envelopeCiphertext("at-1", null), null, null)));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> service.updateCredential(refreshTokenCommand("rt-new")));
        verify(credentialRepository, never()).update(any());
    }

    @Test
    void should_throwConflict_when_updateCredential_given_archivedCredential() {
        // given（已归档凭证不可更新 → 409，且不落更新）
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(archivedCredential("https://mcp.example.com/sse")));

        // when & then
        assertThrows(ResourceConflictException.class, () -> service.updateCredential(tokenCommand("sk-new")));
        verify(credentialRepository, never()).update(any());
    }

    // ==================== addCredential（保管库级约束） ====================

    @Test
    void should_throwConflict_when_addCredential_given_urlAlreadyActive() {
        // given（同一 MCP server 已有活跃凭证 → 409，且不落库）
        stubVaultOwnedActive();
        when(credentialRepository.existsActiveByVaultIdAndUrl("vault_1", "https://mcp.example.com/sse"))
                .thenReturn(true);

        // when & then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.addCredential(new AddVaultCredentialCommand(
                        "vault_1", "static_bearer", "https://mcp.example.com/sse", "sk-plain")));
        assertTrue(ex.getMessage().contains("已存在活跃凭证"));
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void should_throwConflict_when_addCredential_given_activeCountReachedLimit() {
        // given（活跃凭证数已达 20 上限 → 409，且不落库）
        stubVaultOwnedActive();
        when(credentialRepository.existsActiveByVaultIdAndUrl(anyString(), anyString())).thenReturn(false);
        when(credentialRepository.countActiveByVaultId("vault_1")).thenReturn(20L);

        // when & then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.addCredential(new AddVaultCredentialCommand(
                        "vault_1", "static_bearer", "https://mcp.example.com/sse", "sk-plain")));
        assertTrue(ex.getMessage().contains("20"));
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void should_saveCredential_when_addCredential_given_withinLimitAndUrlFree() {
        // given（未达上限且目标 URL 空闲 → 正常落库，密文可解回明文）
        stubVaultOwnedActive();
        when(credentialRepository.existsActiveByVaultIdAndUrl(anyString(), anyString())).thenReturn(false);
        when(credentialRepository.countActiveByVaultId("vault_1")).thenReturn(19L);
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential saved = service.addCredential(new AddVaultCredentialCommand(
                "vault_1", "static_bearer", "https://mcp.example.com/sse", "sk-plain"));

        // then
        assertEquals("https://mcp.example.com/sse", saved.mcpServerUrl());
        assertEquals("sk-plain", encryptionUtil.decrypt(saved.ciphertext()));
    }

    // ==================== search（design D13：total 仅首页 + 在途并发上限） ====================

    @Test
    void should_returnTotal_when_search_given_firstPage() {
        // given（首页：满足筛选条件的总数随首页一并返回）
        when(vaultRepository.findByCursor(eq(1L), any(VaultListFilter.class), eq(21)))
                .thenReturn(List.of(buildVault(1)));
        when(vaultRepository.countByFilter(eq(1L), any(VaultListFilter.class))).thenReturn(7L);
        ListVaultQuery query = new ListVaultQuery(1L, null, "{\"team\":\"data\"}", Boolean.FALSE,
                CursorPageParams.parse(null, null, null));

        // when
        VaultSearchResult result = service.search(query);

        // then
        assertEquals(1, result.page().data().size());
        assertEquals(7L, result.total());
    }

    @Test
    void should_skipCount_when_search_given_cursorPage() {
        // given（翻页请求：探针翻页 MUST NOT 额外执行计数查询）
        Vault anchor = buildVault(9);
        when(vaultRepository.findByVaultId("vault_9")).thenReturn(Optional.of(anchor));
        when(vaultRepository.findByCursor(eq(1L), any(VaultListFilter.class), eq(21))).thenReturn(List.of());
        ListVaultQuery query = new ListVaultQuery(1L, null, null, Boolean.FALSE,
                CursorPageParams.parse(null, "vault_9", null));

        // when
        VaultSearchResult result = service.search(query);

        // then（total 为 null 且计数查询零调用）
        assertNull(result.total());
        verify(vaultRepository, never()).countByFilter(any(), any());
    }

    @Test
    void should_throwTooManyRequests_when_search_given_inFlightOverLimit() throws Exception {
        // given（10 个搜索占满在途许可：每个请求阻塞在库调用上，第 11 个请求进不来）
        CountDownLatch inFlight = new CountDownLatch(10);
        CountDownLatch release = new CountDownLatch(1);
        when(vaultRepository.findByCursor(eq(1L), any(VaultListFilter.class), eq(21)))
                .thenAnswer(invocation -> {
                    inFlight.countDown();
                    release.await();
                    return List.of();
                });
        ListVaultQuery query = new ListVaultQuery(1L, null, null, Boolean.FALSE,
                CursorPageParams.parse(null, null, null));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            for (int i = 0; i < 10; i++) {
                pool.submit(() -> service.search(query));
            }
            assertTrue(inFlight.await(5, TimeUnit.SECONDS), "10 个在途搜索应当全部进入库调用");

            // when & then（许可耗尽 → 快速失败 429，不进入查询）
            assertThrows(TooManyRequestsException.class, () -> service.search(query));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ==================== listCredentials / deleteCredential（tasks 5.1 / 5.3） ====================

    @Test
    void should_pageByCursor_when_listCredentials_given_afterId() {
        // given（凭证列表游标分页：after_id 锚点定位后按降序取 limit+1 行裁切）
        when(vaultRepository.findByVaultId("vault_1")).thenReturn(Optional.of(vaultRow(1L)));
        VaultCredential anchor = credential("https://mcp.example.com/sse", encryptionUtil.encrypt("sk-1"));
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1")).thenReturn(Optional.of(anchor));
        List<VaultCredential> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            rows.add(credential("https://mcp.example.com/" + i, encryptionUtil.encrypt("sk-" + i)));
        }
        when(credentialRepository.findByCursor(eq("vault_1"), any(VaultCredentialListFilter.class), eq(3)))
                .thenReturn(rows);

        // when
        CursorPage<VaultCredential> page = service.listCredentials(new ListVaultCredentialQuery(
                "vault_1", null, Boolean.FALSE, CursorPageParams.parse("2", "cr_1", null)));

        // then（3 行探针 → 裁 2 行且 has_more=true；锚点行位点成对进入过滤器且方向为降序）
        assertEquals(2, page.data().size());
        assertTrue(page.hasMore());
        ArgumentCaptor<VaultCredentialListFilter> captor =
                ArgumentCaptor.forClass(VaultCredentialListFilter.class);
        verify(credentialRepository).findByCursor(eq("vault_1"), captor.capture(), eq(3));
        assertEquals(anchor.createdAt(), captor.getValue().cursorCreatedAt());
        assertEquals(1L, captor.getValue().cursorRowId());
        assertFalse(captor.getValue().reverse());
    }

    @Test
    void should_deleteCredential_when_deleteCredential_given_rowDeleted() {
        // given（逻辑删影响 1 行 → 正常返回）
        when(vaultRepository.findByVaultId("vault_1")).thenReturn(Optional.of(vaultRow(1L)));
        when(credentialRepository.delete("vault_1", "cr_1")).thenReturn(1);

        // when
        service.deleteCredential("vault_1", "cr_1");

        // then
        verify(credentialRepository).delete("vault_1", "cr_1");
    }

    @Test
    void should_throwNotFound_when_deleteCredential_given_noRowDeleted() {
        // given（影响 0 行：凭证不存在 / 已逻辑删 → 404）
        when(vaultRepository.findByVaultId("vault_1")).thenReturn(Optional.of(vaultRow(1L)));
        when(credentialRepository.delete("vault_1", "cr_x")).thenReturn(0);

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> service.deleteCredential("vault_1", "cr_x"));
    }

    @Test
    void should_prefixCredentialIdWithVcred_when_addCredential_given_validCommand() {
        // given（tasks 5.5：新写入凭证 ID 采用契约承诺的 vcred_ 前缀）
        stubVaultOwnedActive();
        when(credentialRepository.existsActiveByVaultIdAndUrl(anyString(), anyString())).thenReturn(false);
        when(credentialRepository.countActiveByVaultId("vault_1")).thenReturn(0L);
        when(credentialRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        VaultCredential saved = service.addCredential(new AddVaultCredentialCommand(
                "vault_1", "static_bearer", "https://mcp.example.com/sse", "sk-plain"));

        // then
        assertNotNull(saved.credentialId());
        assertTrue(saved.credentialId().startsWith("vcred_"), saved.credentialId());
    }

    // ==================== 测试夹具 ====================

    private void stubVaultOwnedActive() {
        when(vaultRepository.findByVaultId("vault_1")).thenReturn(Optional.of(vaultRow(1L)));
    }

    private Vault vaultRow(Long ownerId) {
        return Vault.restore(1L, "vault_1", "数据分析库", null, ownerId, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), "u-1", "u-1");
    }

    private Vault buildVault(int index) {
        return Vault.restore((long) index, "vault_" + index, "库" + index, null, 1L, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), "u-1", "u-1");
    }

    private VaultCredential credential(String mcpServerUrl, byte[] ciphertext) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return VaultCredential.restore(1L, "cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                mcpServerUrl, ciphertext, null, null, null, now, now, "u-1", "u-1");
    }

    private VaultCredential archivedCredential(String mcpServerUrl) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return VaultCredential.restore(1L, "cr_1", "vault_1", VaultCredentialAuthType.STATIC_BEARER,
                mcpServerUrl, encryptionUtil.encrypt("sk-plain"), null, null, now, now, now, "u-1", "u-1");
    }

    /** mcp_oauth 凭证夹具（密文为整包加密的秘密材料信封，见 design D5）。 */
    private VaultCredential oauthCredential(byte[] ciphertext, OffsetDateTime expiresAt, String metadata) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return VaultCredential.restore(1L, "cr_1", "vault_1", VaultCredentialAuthType.MCP_OAUTH,
                "https://mcp.example.com/sse", ciphertext, expiresAt, metadata, null, now, now, "u-1", "u-1");
    }

    /** 秘密材料 → 加密信封密文（复用生产转换器，保证测试与实现同形态）。 */
    private byte[] envelopeCiphertext(String accessToken, VaultCredentialRefresh refresh) {
        return encryptionUtil.encrypt(VaultCredentialMaterialConvert.INSTANCE.toEnvelopeJson(
                new VaultCredentialMaterial(accessToken, refresh)));
    }

    /** 具备刷新配置的 refresh 值对象。 */
    private VaultCredentialRefresh refresh(String refreshToken) {
        return new VaultCredentialRefresh("client-1", refreshToken, "https://auth.example.com/token",
                new VaultCredentialTokenEndpointAuth(VaultCredentialTokenEndpointAuthType.CLIENT_SECRET_BASIC,
                        "cs-1"),
                null, "read write");
    }

    /** 置入 active 的 mcp_oauth 凭证（携带指定信封密文与到期时间）。 */
    private void stubActiveOauthCredential(byte[] ciphertext, OffsetDateTime expiresAt) {
        stubVaultOwnedActive();
        when(credentialRepository.findByVaultIdAndCredentialId("vault_1", "cr_1"))
                .thenReturn(Optional.of(oauthCredential(ciphertext, expiresAt, null)));
    }

    /** 脱敏诊断响应夹具（生产实现仅回传脱敏后正文）。 */
    private static HttpDiagnosticDTO httpResponse(int statusCode, String contentType, String body) {
        return new HttpDiagnosticDTO(statusCode, contentType, body, false);
    }

    /** 桩定 MCP initialize 探测返回指定状态码的响应。 */
    private void stubProbe(int statusCode) {
        when(mcpInitializeProbePort.probe(anyString(), anyString()))
                .thenReturn(new McpProbeOutcomeDTO("initialize",
                        httpResponse(statusCode, "application/json", "{}")));
    }

    /** 桩定 MCP initialize 探测未收到响应（越界 / 连接错误）。 */
    private void stubProbeUnresponsive() {
        when(mcpInitializeProbePort.probe(anyString(), anyString()))
                .thenReturn(new McpProbeOutcomeDTO("initialize", null));
    }

    /** 仅轮换静态凭证明文的更新命令（{@code static_bearer}）。 */
    private static UpdateVaultCredentialCommand tokenCommand(String token) {
        return new UpdateVaultCredentialCommand("vault_1", "cr_1", "static_bearer",
                token, true, null, false, null, false,
                null, false, null, false, null, false, null, false);
    }

    /** 仅轮换 OAuth 访问令牌的更新命令（{@code mcp_oauth}）。 */
    private static UpdateVaultCredentialCommand accessTokenCommand(String accessToken) {
        return new UpdateVaultCredentialCommand("vault_1", "cr_1", "mcp_oauth",
                null, false, accessToken, true, null, false,
                null, false, null, false, null, false, null, false);
    }

    /** 仅修补刷新令牌的更新命令（{@code mcp_oauth}）。 */
    private static UpdateVaultCredentialCommand refreshTokenCommand(String refreshToken) {
        return new UpdateVaultCredentialCommand("vault_1", "cr_1", "mcp_oauth",
                null, false, null, false, null, false,
                refreshToken, true, null, false, null, false, null, false);
    }

    /** 仅提交元数据增量（或整体重置）的更新命令（不含 auth 补丁）。 */
    private static UpdateVaultCredentialCommand metadataCommand(String metadataMerge) {
        return new UpdateVaultCredentialCommand("vault_1", "cr_1", null,
                null, false, null, false, null, false,
                null, false, null, false, null, false, metadataMerge, true);
    }
}
