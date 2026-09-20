package com.linkroa.deepdataagent.runtime.application.validation;

import com.linkroa.deepdataagent.agent.api.EnvironmentApi;
import com.linkroa.deepdataagent.file.api.FileApi;
import com.linkroa.deepdataagent.file.api.dto.FileMountMetaDTO;
import com.linkroa.deepdataagent.memory.api.MemoryStoreApi;
import com.linkroa.deepdataagent.memory.api.dto.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.SessionBusyException;
import com.linkroa.deepdataagent.vault.api.VaultReferenceApi;
import com.linkroa.deepdataagent.vault.api.dto.VaultReferenceDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SessionMountValidator} 挂载前置校验单测（decompose-command-facade 2.1 自
 * 门面壳测试的创建会话校验分区整体迁移，异常语义与断言不减量）。
 * <p>校验器无持久化 / 物化协作者，故本类只钉「校验规则本身的判定与异常语义」；
 * 「校验失败早于物化与落库」的编排时序由会话生命周期服务用例
 * （{@code should_skipMaterializeAndPersist_when_createSession_given_mountValidationFailure}）固化断言。</p>
 */
@ExtendWith(MockitoExtension.class)
class SessionMountValidatorTest {

    /** 就绪文件字节数（远低于配额上限，用于「仅就绪门禁失败」之外的合法桩）。 */
    private static final long READY_SIZE = 1024L;

    @Mock private EnvironmentApi environmentApi;
    @Mock private FileApi fileApi;
    @Mock private MemoryStoreApi memoryStoreApi;
    @Mock private VaultReferenceApi vaultReferenceApi;

    private SessionMountValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SessionMountValidator();
        ReflectionTestUtils.setField(validator, "environmentApi", environmentApi);
        ReflectionTestUtils.setField(validator, "fileApi", fileApi);
        ReflectionTestUtils.setField(validator, "memoryStoreApi", memoryStoreApi);
        ReflectionTestUtils.setField(validator, "vaultReferenceApi", vaultReferenceApi);
    }

    /** 携带挂载资源的创建命令（数字 user_id 字符串形态，owner 解析为 1L）。 */
    private CreateSessionCommand commandWithResources(List<SessionResource> resources) {
        return new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, resources);
    }

    // ==================== 运行环境校验 ====================

    @Test
    void should_skipContractQuery_when_validateEnvironment_given_blankEnvironmentId() {
        // given（未指定运行环境：应用层可空，会话可后挂环境）
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}");

        // when & then（空白环境直接放行，环境类型解析零触达）
        assertDoesNotThrow(() -> validator.validateEnvironment(command));
        verify(environmentApi, never()).resolveType(any(), any());
    }

    @Test
    void should_pass_when_validateEnvironment_given_cloudEnvironment() {
        // given（可执行平面的环境存在且归属当前用户）
        when(environmentApi.resolveType(1L, "env-1")).thenReturn("cloud");
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), "env-1", List.of(), null);

        // when & then（合法环境放行）
        assertDoesNotThrow(() -> validator.validateEnvironment(command));
        verify(environmentApi).resolveType(1L, "env-1");
    }

    @Test
    void should_throwNotFound_when_validateEnvironment_given_unresolvableEnvironment() {
        // given（挂接的运行环境不存在 / 越权：类型解析返回 null）
        when(environmentApi.resolveType(1L, "env-x")).thenReturn(null);
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), "env-x", List.of(), null);

        // when & then（统一 404 语义，不泄露存在性）
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> validator.validateEnvironment(command));
        assertTrue(ex.getMessage().contains("env-x"), "错误消息应携带环境定位信息");
    }

    @Test
    void should_throwNotFound_when_validateEnvironment_given_nonNumericUserId() {
        // given（owner 非法（非数字 user_id）：解析收敛为 null，门禁按不存在处理、零契约触达）
        CreateSessionCommand command = new CreateSessionCommand("not-a-number", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), "env-x", List.of(), null);

        // when & then（与逐项查询同口径：null owner 一律 404）
        assertThrows(ResourceNotFoundException.class, () -> validator.validateEnvironment(command));
        verify(environmentApi, never()).resolveType(any(), any());
    }

    @Test
    void should_throwIllegalArgument_when_validateEnvironment_given_selfHostedEnvironment() {
        // given（self_hosted 执行平面：本期运行时不可执行）
        when(environmentApi.resolveType(1L, "env-sh")).thenReturn("self_hosted");
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), "env-sh", List.of(), null);

        // when & then（400 语义拒绝，消息携带执行平面定位）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validateEnvironment(command));
        assertTrue(ex.getMessage().contains("self_hosted"), "错误消息应指明不支持的执行平面类型");
    }

    // ==================== 挂载文件校验 ====================

    @Test
    void should_passWithoutContractQuery_when_validateMountedFiles_given_noFileResources() {
        // given（批次内仅记忆库挂载，无 file 类型资源）
        CreateSessionCommand command = commandWithResources(
                List.of(SessionResource.memoryStore("ms_1", "read_only", null)));

        // when & then（无文件即零就绪查询、整批放行）
        assertDoesNotThrow(() -> validator.validateMountedFiles(command));
        verifyNoInteractions(fileApi);
    }

    @Test
    void should_throwNotFound_when_validateMountedFiles_given_missingReadyMountMeta() {
        // given（挂载文件不存在 / 越权 / 未就绪：就绪元数据解析为空）
        when(fileApi.findReadyMountMeta("file_1", 1L)).thenReturn(Optional.empty());
        CreateSessionCommand command = commandWithResources(List.of(SessionResource.file("file_1", null)));

        // when & then（统一 404 语义，与文件读取端点同语义、不泄露存在性）
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> validator.validateMountedFiles(command));
        assertTrue(ex.getMessage().contains("file_1"), "错误消息应携带文件定位信息");
        verify(fileApi).findReadyMountMeta("file_1", 1L);
    }

    @Test
    void should_throwSessionBusy_when_validateMountedFiles_given_fileExistsButNotReady() {
        // given（文件存在且归属当前用户，但 status 非 ready：就绪元数据为空、二次探测命中）
        when(fileApi.findReadyMountMeta("file_1", 1L)).thenReturn(Optional.empty());
        when(fileApi.existsOwnedBy("file_1", 1L)).thenReturn(true);
        CreateSessionCommand command = commandWithResources(List.of(SessionResource.file("file_1", null)));

        // when & then（契约明文：存在但未就绪 → 409 invalid_request_error，与「不存在 / 越权 → 404」分流）
        SessionBusyException ex = assertThrows(SessionBusyException.class,
                () -> validator.validateMountedFiles(command));
        assertTrue(ex.getMessage().contains("file_1"), "错误消息应携带文件定位信息");
        verify(fileApi).existsOwnedBy("file_1", 1L);
    }

    @Test
    void should_throwNotFound_when_validateMountedFiles_given_nonNumericUserId() {
        // given（owner 非法：就绪查询按 null owner 一律未命中 → 不就绪）
        when(fileApi.findReadyMountMeta("file_1", null)).thenReturn(Optional.empty());
        CreateSessionCommand command = new CreateSessionCommand("not-a-number", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(SessionResource.file("file_1", null)));

        // when & then（不就绪统一 404，不泄露存在性）
        assertThrows(ResourceNotFoundException.class, () -> validator.validateMountedFiles(command));
        verify(fileApi).findReadyMountMeta("file_1", null);
    }

    @Test
    void should_throwIllegalArgument_when_validateMountedFiles_given_duplicateFileIdsInBatch() {
        // given（批内同一 file_id 出现两条：判重拒绝）
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", READY_SIZE)));
        CreateSessionCommand command = commandWithResources(List.of(
                SessionResource.file("file_1", null), SessionResource.file("file_1", "mounts/x.txt")));

        // when & then（创建路径判重收敛 400，与「批量挂载单条非法 → 整批拒绝」基线契约一致）
        assertThrows(IllegalArgumentException.class, () -> validator.validateMountedFiles(command));
    }

    @Test
    void should_throwIllegalArgument_when_validateMountedFiles_given_duplicateMountPathsInBatch() {
        // given（不同 file_id 但缺省补全后 mount_path 相同：路径占用判重拒绝）
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", READY_SIZE)));
        CreateSessionCommand command = commandWithResources(List.of(
                SessionResource.file("file_1", "mounts/a.txt"),
                SessionResource.file("file_2", "mounts/a.txt")));

        // when & then（字节清单整批材料化后由聚合按「判重 → 就绪 → 配额」顺序判定：路径占用胜出
        // ——file_2 未桩即清单缺键，若不就绪判定先于判重则会落 404）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validateMountedFiles(command));
        assertTrue(ex.getMessage().contains("挂载路径已被占用"), "判重 MUST 先于不就绪的 404 判定");
    }

    @Test
    void should_throwIllegalArgument_when_validateMountedFiles_given_batchExceedingQuota() {
        // given（批内累计 400MB + 150MB → 超 500MB 上限：整批 400 拒绝）
        when(fileApi.findReadyMountMeta("file_1", 1L)).thenReturn(Optional.of(
                new FileMountMetaDTO("file_1", "big.bin", 400L * 1024 * 1024)));
        when(fileApi.findReadyMountMeta("file_2", 1L)).thenReturn(Optional.of(
                new FileMountMetaDTO("file_2", "bigger.bin", 150L * 1024 * 1024)));
        CreateSessionCommand command = commandWithResources(List.of(
                SessionResource.file("file_1", null), SessionResource.file("file_2", null)));

        // when & then（配额判定在创建路径以「既有挂载恒为空」参与求和）
        assertThrows(IllegalArgumentException.class, () -> validator.validateMountedFiles(command));
    }

    @Test
    void should_throwNotFound_when_validateMountedFiles_given_triggeredCommandWithInvalidMount() {
        // given（调度器触发路径：命令携 cron 触发标记 + 未就绪挂载文件——触发会话与 HTTP 入口同走一套校验）
        when(fileApi.findReadyMountMeta("file_ghost", 1L)).thenReturn(Optional.empty());
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", null, null,
                "cron", "dep_1", List.of(SessionResource.file("file_ghost", null)));

        // when & then（触发标记不影响校验口径，同样被 404 拒绝）
        assertThrows(ResourceNotFoundException.class, () -> validator.validateMountedFiles(command));
    }

    // ==================== 挂载字节清单材料化 ====================

    @Test
    void should_putReadySizeAndSkipOthers_when_mountedSizeBytes_given_mixedResourceTypes() {
        // given（file 就绪 / file 未就绪 / memory_store 三类混合）
        when(fileApi.findReadyMountMeta("file_ready", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_ready", "a.txt", 2048L)));
        when(fileApi.findReadyMountMeta("file_ghost", 1L)).thenReturn(Optional.empty());

        // when（清单材料化：仅 file 类型参与，未命中不落键）
        Map<String, Long> sizes = validator.mountedSizeBytes(List.of(
                SessionResource.file("file_ready", null),
                SessionResource.file("file_ghost", null),
                SessionResource.memoryStore("ms_1", "read_only", null)), 1L);

        // then（就绪项计入、未就绪项缺键（由领域按「元数据缺失计 0」参与求和）、非 file 类型不查询）
        assertEquals(Map.of("file_ready", 2048L), sizes);
        verify(fileApi).findReadyMountMeta("file_ready", 1L);
        verify(fileApi).findReadyMountMeta("file_ghost", 1L);
        // 仅两个 file 资源触发查询：memory_store 类型零就绪查询（否则此处计数为 3）
        verify(fileApi, times(2)).findReadyMountMeta(any(), any());
    }

    // ==================== 挂载记忆库校验 ====================

    @Test
    void should_passWithoutContractQuery_when_validateMountedMemoryStores_given_noMemoryStoreResources() {
        // given（批次内仅 file 挂载）
        CreateSessionCommand command = commandWithResources(List.of(SessionResource.file("file_1", null)));

        // when & then（无记忆库即零批量解析）
        assertDoesNotThrow(() -> validator.validateMountedMemoryStores(command));
        verifyNoInteractions(memoryStoreApi);
    }

    @Test
    void should_pass_when_validateMountedMemoryStores_given_allOwnedStoresResolved() {
        // given（记忆库全部命中归属解析结果）
        when(memoryStoreApi.resolveByIds(1L, List.of("ms_1")))
                .thenReturn(List.of(new MemoryStoreReferenceDTO("ms_1", "记忆库一")));
        CreateSessionCommand command = commandWithResources(
                List.of(SessionResource.memoryStore("ms_1", "read_only", null)));

        // when & then（合法批放行）
        assertDoesNotThrow(() -> validator.validateMountedMemoryStores(command));
        verify(memoryStoreApi).resolveByIds(1L, List.of("ms_1"));
    }

    @Test
    void should_throwNotFound_when_validateMountedMemoryStores_given_missingMemoryStore() {
        // given（挂载记忆库不存在 / 越权：差集非空 → 全有或全无）
        when(memoryStoreApi.resolveByIds(1L, List.of("ms_ghost"))).thenReturn(List.of());
        CreateSessionCommand command = commandWithResources(
                List.of(SessionResource.memoryStore("ms_ghost", null, null)));

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> validator.validateMountedMemoryStores(command));
        assertTrue(ex.getMessage().contains("ms_ghost"), "错误消息应携带记忆库定位信息");
    }

    @Test
    void should_throwNotFound_when_validateMountedMemoryStores_given_nonNumericUserId() {
        // given（owner 非法：解析为 null 时零契约触达，直接按差集非空处理）
        CreateSessionCommand command = new CreateSessionCommand("not-a-number", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(SessionResource.memoryStore("ms_1", null, null)));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> validator.validateMountedMemoryStores(command));
        verify(memoryStoreApi, never()).resolveByIds(anyLong(), anyList());
    }

    // ==================== 保管库挂载校验 ====================

    @Test
    void should_passWithoutContractQuery_when_validateVaults_given_emptyVaultIds() {
        // given（未携带保管库挂载）
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}");

        // when & then（空列表直接放行，保管库契约零触达）
        assertDoesNotThrow(() -> validator.validateVaults(command));
        verifyNoInteractions(vaultReferenceApi);
    }

    @Test
    void should_pass_when_validateVaults_given_allOwnedVaultsResolved() {
        // given（保管库全部命中归属解析结果）
        when(vaultReferenceApi.resolveByIds(1L, List.of("vault_1")))
                .thenReturn(List.of(new VaultReferenceDTO("vault_1", "保管库一")));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), null, List.of("vault_1"), null);

        // when & then
        assertDoesNotThrow(() -> validator.validateVaults(command));
        verify(vaultReferenceApi).resolveByIds(1L, List.of("vault_1"));
    }

    @Test
    void should_throwNotFound_when_validateVaults_given_missingVault() {
        // given（保管库差集：vault_missing 不存在 / 越权，不出现在解析结果中）
        when(vaultReferenceApi.resolveByIds(1L, List.of("vault_1", "vault_missing")))
                .thenReturn(List.of(new VaultReferenceDTO("vault_1", "保管库一")));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), null, List.of("vault_1", "vault_missing"), null);

        // when & then（统一 404，不泄露存在性）
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> validator.validateVaults(command));
        assertTrue(ex.getMessage().contains("vault_missing"), "错误消息应携带保管库定位信息");
    }

    @Test
    void should_throwNotFound_when_validateVaults_given_nonNumericUserId() {
        // given（owner 非法：解析为 null 时零契约触达，直接按差集非空处理）
        CreateSessionCommand command = new CreateSessionCommand("not-a-number", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), null, List.of("vault_1"), null);

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> validator.validateVaults(command));
        verify(vaultReferenceApi, never()).resolveByIds(anyLong(), anyList());
    }
}
