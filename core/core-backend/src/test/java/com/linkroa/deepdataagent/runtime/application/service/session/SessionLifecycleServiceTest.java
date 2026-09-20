package com.linkroa.deepdataagent.runtime.application.service.session;

import com.linkroa.deepdataagent.agent.api.dto.AgentSnapshotDTO;
import com.linkroa.deepdataagent.file.api.dto.FileMountMetaDTO;
import com.linkroa.deepdataagent.memory.api.dto.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.command.SendMessageCommand;
import com.linkroa.deepdataagent.runtime.application.command.UpdateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.service.AgentRuntimeServiceTestSupport;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignal;
import com.linkroa.deepdataagent.runtime.domain.event.AgentStreamSignalType;
import com.linkroa.deepdataagent.runtime.domain.event.TurnFinished;
import com.linkroa.deepdataagent.runtime.domain.factory.BuiltAgent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.domain.model.Transition;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.SessionBusyException;
import com.linkroa.deepdataagent.vault.api.dto.VaultReferenceDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialResolutionPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SessionLifecycleService} 单测（decompose-command-facade 4.3：会话簇用例自
 * 门面壳测试类整体迁移，断言与桩一字未减，仅调用目标改指本服务）。
 * <p>夹具口径与壳测试一致（装配见 {@link AgentRuntimeServiceTestSupport}）：真实
 * {@link SessionLifecycleService} + 真实 {@code SessionMountValidator} / {@code TurnEventWriter}
 * + 仓储 / 跨 BC 契约 / 物化编排 / 事务模板为替身，令「校验全前置即零物化零落库」「入库失败补偿」
 * 「中断经 DB CAS + 本地推流收敛」三条会话侧红线由端到端固化断言守护。</p>
 * <p><b>中断路径不依赖执行链</b>（design D3 无边）：本类用例对 {@code coordinationLeaseService}
 * 的断言仅覆盖「无活跃执行时零触碰协调层」，取消信号一律经 {@code sessionRegistry} 的进程内
 * 会话上下文与持久 {@code canceling} 痕迹表达。</p>
 */
class SessionLifecycleServiceTest extends AgentRuntimeServiceTestSupport {

    /** 发布号非法场景的校验桩（公共 404 语义，assertResolvable 为 void 用 doThrow 桩）。 */
    private void doThrowNotFound() {
        doThrow(new ResourceNotFoundException("发布号格式非法"))
                .when(runtimeAgentAssemblyService).assertResolvable(anyString(), anyString(), anyLong());
    }

    // ==================== 会话管理 / 挂载资源 / 中断（自壳类整体迁入） ====================

    @Test
    void should_createSession_when_createSession_given_validCommand() {
        // given（校验链路默认无操作即通过：发布号合法 / Agent 与版本均存在）
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}");

        // when
        AgentSession session = sessionLifecycleService.createSession(command);

        // then（校验通过后落库，会话初始为双字段状态机 idle / idle）
        assertEquals("1", session.userId());
        assertEquals(AgentSessionStatus.IDLE, session.status());
        assertEquals(TurnPhase.IDLE, session.turnPhase());
        verify(runtimeAgentAssemblyService).assertResolvable("agent-a", "1.0.0", 1L);
        verify(sessionRepository).save(any(AgentSession.class));
    }

    @Test
    void should_throwNotFound_when_createSession_given_invalidReleaseNumber() {
        // given（发布号非十进制 → 404，会话不落库）
        wireTransactionTemplate();
        doThrowNotFound();
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "v1", "会话", "{}");

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> sessionLifecycleService.createSession(command));
        verify(sessionRepository, never()).save(any(AgentSession.class));
    }

    @Test
    void should_throwNotFound_when_createSession_given_missingVersion() {
        // given（版本不存在 → 404，无全局回退）
        wireTransactionTemplate();
        doThrow(new ResourceNotFoundException("Agent版本不存在"))
                .when(runtimeAgentAssemblyService).assertResolvable(anyString(), anyString(), anyLong());
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "99", "会话", "{}");

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> sessionLifecycleService.createSession(command));
        verify(sessionRepository, never()).save(any(AgentSession.class));
    }

    @Test
    void should_throwNotFound_when_createSession_given_missingAgentOrArchived() {
        // given（Agent 不存在 / 已归档 → 404，拒绝创建新会话）
        wireTransactionTemplate();
        doThrow(new ResourceNotFoundException("Agent已归档，不可创建新会话"))
                .when(runtimeAgentAssemblyService).assertResolvable(anyString(), anyString(), anyLong());
        CreateSessionCommand command = new CreateSessionCommand("1", "ghost", "1", "会话", "{}");

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> sessionLifecycleService.createSession(command));
        verify(sessionRepository, never()).save(any(AgentSession.class));
    }

    @Test
    void should_resolveActiveVersion_when_createSession_given_blankVersion() {
        // given（省略版本 → 解析激活版本并物化到会话）
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(runtimeAgentAssemblyService.activeVersionNumber("agent-a", 1L)).thenReturn("2");
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", null, "会话", "{}");

        // when
        AgentSession session = sessionLifecycleService.createSession(command);

        // then（未走显式校验链，版本物化为激活版本 "2"）
        assertEquals("2", session.agentVersion());
        verify(runtimeAgentAssemblyService).activeVersionNumber("agent-a", 1L);
        verify(runtimeAgentAssemblyService, never()).assertResolvable(anyString(), anyString(), anyLong());
        verify(sessionRepository).save(any(AgentSession.class));
    }

    @Test
    void should_persistSessionWithMounts_when_createSession_given_mountedFiles() {
        // given（挂载文件归属 + 就绪校验通过：合法批整体放行）
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", 1024L)));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(SessionResource.file("file_1", "mounts/a.txt")));

        // when
        AgentSession session = sessionLifecycleService.createSession(command);

        // then（挂载资源随会话落库，版本物化重建不丢弃挂载引用；先物化后入库的事务序）
        assertEquals(1, session.resources().size());
        assertEquals("file_1", session.resources().get(0).fileId());
        assertEquals("mounts/a.txt", session.resources().get(0).mountPath());
        InOrder materializeFirst = org.mockito.Mockito.inOrder(sessionMountMaterializer, sessionRepository);
        materializeFirst.verify(sessionMountMaterializer).materializeAll(any(AgentSession.class));
        materializeFirst.verify(sessionRepository).save(any(AgentSession.class));
        verify(sessionRepository).save(argThat(s -> s.resources().size() == 1
                && "file_1".equals(s.resources().get(0).fileId())));
    }

    @Test
    void should_skipMaterializeAndPersist_when_createSession_given_mountValidationFailure() {
        // given（挂载校验失败（未就绪文件）：decompose-command-facade 2.1 后，判重 / 就绪 / 配额
        //       的判定与异常语义由 SessionMountValidatorTest 逐条固化，本用例留守创建会话的
        //       编排红线——校验 MUST 早于任何物化落盘与入库，非法批 MUST NOT 留下宿主副本）
        wireTransactionTemplate();
        when(fileApi.findReadyMountMeta("file_1", 1L)).thenReturn(Optional.empty());
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(SessionResource.file("file_1", null)));

        // when & then（校验拒绝即整批终止：零物化 + 零落库，会话不产生）
        assertThrows(ResourceNotFoundException.class, () -> sessionLifecycleService.createSession(command));
        verify(sessionMountMaterializer, never()).materializeAll(any(AgentSession.class));
        verify(sessionRepository, never()).save(any(AgentSession.class));
    }

    @Test
    void should_skipMaterializeAndPersist_when_createSession_given_illegalEnvironmentVariables() {
        // given（环境变量名不匹配 [A-Za-z_][A-Za-z0-9_]*：判定项本体由
        //       SessionEnvironmentVariablesValidatorTest 逐条固化，本用例只留守创建路径的编排红线
        //       ——环境变量形态校验与挂载校验同层同序，非法值 MUST 早于物化落盘与入库）
        wireTransactionTemplate();
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), null, List.of(), "{\"1BAD\":\"v\"}");

        // when & then（400 语义：整批终止，零物化 + 零落库）
        assertThrows(IllegalArgumentException.class, () -> sessionLifecycleService.createSession(command));
        verify(sessionMountMaterializer, never()).materializeAll(any(AgentSession.class));
        verify(sessionRepository, never()).save(any(AgentSession.class));
    }

    @Test
    void should_rejectCreateWithoutSave_when_createSession_given_materializeFailure() {
        // given（校验通过但物化失败（内容缺失 / 摘要不符）：以可读错误拒绝创建，会话不落库；
        // 本轮已物化项由物化器内部补偿（其单测覆盖），服务侧只需不再落库）
        wireTransactionTemplate();
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", 1024L)));
        doThrow(new IllegalArgumentException("挂载文件不存在、无权访问或内容不可用，拒绝挂载: file_1"))
                .when(sessionMountMaterializer).materializeAll(any(AgentSession.class));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(SessionResource.file("file_1", null)));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> sessionLifecycleService.createSession(command));
        verify(sessionRepository, never()).save(any(AgentSession.class));
        // 物化未成功即无「先物化后入库」的补偿义务，命名空间清理零触达
        verify(sessionMountMaterializer, never()).releaseAll(any(AgentSession.class));
    }

    @Test
    void should_compensateReleaseAll_when_createSession_given_saveFailure() {
        // given（物化成功但入库失败：删除已物化副本不留孤儿，主异常原样上抛）
        wireTransactionTemplate();
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", 1024L)));
        doThrow(new RuntimeException("db down")).when(sessionRepository).save(any(AgentSession.class));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(SessionResource.file("file_1", null)));

        // when & then
        assertThrows(RuntimeException.class, () -> sessionLifecycleService.createSession(command));
        verify(sessionMountMaterializer).releaseAll(any(AgentSession.class));
    }

    @Test
    void should_skipEnvironmentGuard_when_createSession_given_blankEnvironment() {
        // given（未指定运行环境（应用层可空，会话可后挂）：跳过执行平面守卫，不查询环境契约）
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}");

        // when
        AgentSession session = sessionLifecycleService.createSession(command);

        // then（无环境直接落库，环境类型解析零触达）
        assertNull(session.environmentId());
        verify(environmentApi, never()).resolveType(any(), any());
        verify(sessionRepository).save(any(AgentSession.class));
    }

    @Test
    void should_persistFullMounts_when_createSession_given_environmentVaultsMemoryStoreAndEnvVars() {
        // given（全量挂载：cloud 环境 + 保管库 + 文件 / 记忆库混合挂载 + 环境变量）
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(environmentApi.resolveType(1L, "env-1")).thenReturn("cloud");
        when(vaultReferenceApi.resolveByIds(1L, List.of("vault_1")))
                .thenReturn(List.of(new VaultReferenceDTO("vault_1", "保管库一")));
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", 1024L)));
        when(memoryStoreApi.resolveByIds(1L, List.of("ms_1")))
                .thenReturn(List.of(new MemoryStoreReferenceDTO("ms_1", "记忆库一")));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null,
                List.of(SessionResource.file("file_1", null), SessionResource.memoryStore("ms_1", "read_only", null)),
                "env-1", List.of("vault_1"), "{\"K\":\"V\"}");

        // when
        AgentSession session = sessionLifecycleService.createSession(command);

        // then（环境 / 保管库 / 环境变量透传落库，记忆库引用由挂载资源派生）
        assertEquals("env-1", session.environmentId());
        assertEquals(List.of("vault_1"), session.vaultIds());
        assertEquals("{\"K\":\"V\"}", session.environmentVariables());
        assertEquals(List.of("ms_1"), session.memoryStoreIds());
        assertEquals(2, session.resources().size());
    }

    @Test
    void should_notResolveCredentials_when_createSession_given_vaultIds() {
        // given（D2.1 绑定与解析分离：创建路径只写引用，凭据解析推迟到运行时装配）
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(vaultReferenceApi.resolveByIds(1L, List.of("vault_1")))
                .thenReturn(List.of(new VaultReferenceDTO("vault_1", "保管库一")));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", "会话", "{}",
                null, null, List.of(), null, List.of("vault_1"), null);

        // when
        AgentSession session = sessionLifecycleService.createSession(command);

        // then（保管库只经元数据面校验；零装配、零凭据材料化——创建用例结构上不持有材料化端口）
        verify(vaultReferenceApi).resolveByIds(1L, List.of("vault_1"));
        verify(runtimeAgentAssemblyService, never()).assemble(any());
        assertEquals(List.of("vault_1"), session.vaultIds());
        assertTrue(Arrays.stream(SessionLifecycleService.class.getDeclaredFields())
                .noneMatch(field -> VaultCredentialResolutionPort.class.equals(field.getType())));
    }

    @Test
    void should_preserveTriggerMark_when_createSession_given_triggeredCommand() {
        // given（调度器触发：触发标记须随会话落库，不得在版本物化重建中丢失）
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        CreateSessionCommand command = new CreateSessionCommand("1", "agent-a", "1.0.0", null, null,
                "webhook", "dep-1");

        // when
        AgentSession session = sessionLifecycleService.createSession(command);

        // then
        assertEquals("webhook", session.triggerType());
        assertEquals("dep-1", session.triggerId());
    }

    @Test
    void should_persistMainThread_when_createSession_given_basicCommand() {
        // given（6.3：会话创建随行落协调器主线程；快照经契约解析并移除 multiagent 键）
        wireTransactionTemplate();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentSnapshotApi.resolveSnapshot(eq("agent-a"), any(), eq(1L))).thenReturn(new AgentSnapshotDTO(
                "agent-a", 1,
                new java.util.LinkedHashMap<>(Map.of("id", "agent-a", "name", "分析助手",
                        "multiagent", Map.of("type", "coordinator")))));

        // when
        AgentSession session = sessionLifecycleService.createSession(new CreateSessionCommand("1", "agent-a", "1.0.0",
                null, null, null, null));

        // then（主线程：sthr_ 前缀 + 归属会话 + parent=null + idle，快照裁剪去 multiagent）
        ArgumentCaptor<SessionThread> captor = ArgumentCaptor.forClass(SessionThread.class);
        verify(sessionThreadRepository).save(captor.capture());
        SessionThread main = captor.getValue();
        assertTrue(main.threadId().startsWith("sthr_"));
        assertEquals(session.sessionId(), main.sessionId());
        assertNull(main.parentThreadId());
        assertTrue(main.isMainThread());
        assertEquals(AgentSessionStatus.IDLE, main.status());
        assertTrue(main.agent().contains("分析助手"));
        assertFalse(main.agent().contains("multiagent"));
    }

    @Test
    void should_throwNotFound_when_deleteSession_given_missingSession() {
        // given
        when(sessionRepository.findBySessionId("nope")).thenReturn(Optional.empty());

        // when & then（会话不存在 → 404 not_found_error）
        assertThrows(ResourceNotFoundException.class, () -> sessionLifecycleService.deleteSession("nope"));
    }

    @Test
    void should_deleteSessionWithEventsAndArtifacts_when_deleteSession_given_existingSession() {
        // given
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        bindConnection(session);

        // when
        sessionLifecycleService.deleteSession(session.sessionId());

        // then（会话与事件流 / 线程同事务逻辑删；运行现场清理：句柄释放 + 注册表移除 + 产出的工件删除
        // + 宿主会话命名空间目录清理）
        verify(sessionRepository).deleteBySessionId(session.sessionId());
        verify(chatEventRepository).deleteBySessionId(session.sessionId());
        verify(sessionThreadRepository).deleteBySessionId(session.sessionId());
        verify(connectionHandle).close();
        assertTrue(sessionRegistry.get(session.sessionId()).isEmpty());
        verify(artifactDeliveryPort).deleteSessionArtifacts(session.sessionId());
        verify(sessionMountMaterializer).releaseAll(any(AgentSession.class));
    }

    @Test
    void should_stillDelete_when_deleteSession_given_artifactCleanupFailure() {
        // given：产出清理抛异常仅告警，不阻断会话删除
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(artifactDeliveryPort.deleteSessionArtifacts(session.sessionId()))
                .thenThrow(new RuntimeException("db down"));

        // when
        sessionLifecycleService.deleteSession(session.sessionId());

        // then
        verify(sessionRepository).deleteBySessionId(session.sessionId());
    }

    @Test
    void should_deleteWithoutSceneCleanup_when_deleteSession_given_waitingConfirmationSession() {
        // given（durable HITL：等待态会话删除——未应答明细随会话终局即合法终局，无任何现场清理依赖）
        AgentSession session = idleSession().withPhase(TurnPhase.AWAITING_CONFIRMATION);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        bindConnection(session);

        // when
        sessionLifecycleService.deleteSession(session.sessionId());

        // then：删除链与 idle 态完全同构，不触碰 HITL 通道（不作废等待、不续跑）
        verify(sessionRepository).deleteBySessionId(session.sessionId());
        verify(chatEventRepository).deleteBySessionId(session.sessionId());
        verify(connectionHandle).close();
        assertTrue(sessionRegistry.get(session.sessionId()).isEmpty());
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.ABANDON_WAITING_CONFIRMATION));
        verify(agentRunExecutor, never()).resumeConfirmation(any(BuiltAgent.class), anyList(),
                anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void should_updateTitleOnlyAndPublishSessionUpdated_when_updateSession_given_titleCommand() {
        // given（title-only 更新：metadata / 环境变量两列不触碰）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(chatEventRepository.nextSequenceNum(session.sessionId())).thenReturn(1L);

        // when
        sessionLifecycleService.updateSession(new UpdateSessionCommand(session.sessionId(), true, "新标题", null, null));

        // then（同事务落库 session.updated 事件）
        verify(sessionRepository).updateProfile(session.sessionId(), "新标题", true, null, null);
        verify(chatEventRepository).save(argThat(e -> e.type() == ChatEventType.SESSION_UPDATED
                && e.sessionId().equals(session.sessionId())));
    }

    @Test
    void should_mergeMetadataShallowly_when_updateSession_given_incomingMetadata() {
        // given（既有 a=1 b=2，增量 b=3 → 合并后保留 a、覆盖 b；环境变量不触碰）
        AgentSession session = sessionWithMetadata("{\"a\":1,\"b\":2}");
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(chatEventRepository.nextSequenceNum(session.sessionId())).thenReturn(1L);

        // when
        sessionLifecycleService.updateSession(new UpdateSessionCommand(session.sessionId(), false, null, "{\"b\":3}", null));

        // then
        verify(sessionRepository).updateProfile(eq(session.sessionId()), isNull(), eq(false),
                argThat(json -> json.contains("\"a\":1") && json.contains("\"b\":3")), isNull());
    }

    @Test
    void should_replaceEnvironmentVariables_when_updateSession_given_envVarsJson() {
        // given（环境变量整体替换语义：提供即覆盖，metadata 缺省不改）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(chatEventRepository.nextSequenceNum(session.sessionId())).thenReturn(1L);

        // when
        sessionLifecycleService.updateSession(new UpdateSessionCommand(session.sessionId(), false, null, null, "{\"K\":\"V\"}"));

        // then
        verify(sessionRepository).updateProfile(session.sessionId(), null, false, null, "{\"K\":\"V\"}");
    }

    @Test
    void should_keepExistingEnvVars_when_updateSession_given_illegalEnvironmentVariables() {
        // given（以保留前缀变量整体替换既有环境变量：形态校验与创建路径共用同一判定）
        AgentSession session = idleSession();

        // when & then（400 语义：零列更新 + 零事件——既有环境变量保持原值；
        //             校验在方法体首行、早于会话查询与事务，故会话仓储零交互、无需事务夹具）
        assertThrows(IllegalArgumentException.class, () -> sessionLifecycleService.updateSession(
                new UpdateSessionCommand(session.sessionId(), false, null, null, "{\"CAW_TOKEN\":\"v\"}")));
        verify(sessionRepository, never()).findBySessionId(anyString());
        verify(sessionRepository, never()).updateProfile(any(), any(), anyBoolean(), any(), any());
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    // ==================== 创建后追加挂载（appendResources） ====================

    @Test
    void should_appendAndPersistMerged_when_appendResources_given_idleSessionWithExistingMount() {
        // given（会话已挂 file_1，追加 file_2：总量在限内，合并整列一次落库）
        AgentSession session = AgentSession.createWithTrigger("1", "agent-a", "1.0.0", "{}", null, null, null,
                List.of(SessionResource.file("file_1", null)));
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", 1024L)));
        when(fileApi.findReadyMountMeta("file_2", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_2", "b.txt", 2048L)));

        // when
        List<SessionResource> appended = sessionLifecycleService.appendResources(session.sessionId(),
                List.of(SessionResource.file("file_2", null)));

        // then（返回追加项：自动生成 sesr_ 资源 ID、缺省挂载路径已归一）
        assertEquals(1, appended.size());
        assertTrue(appended.get(0).id().startsWith(SessionResource.RESOURCE_ID_PREFIX));
        assertEquals("mounts/file_2", appended.get(0).mountPath());
        // 仓储收到「既有 + 追加」合并列表（保序）
        verify(sessionRepository).updateResources(eq(session.sessionId()), argThat(list ->
                list.size() == 2 && "file_1".equals(list.get(0).fileId()) && "file_2".equals(list.get(1).fileId())));
        // 先物化新增项（不含既有挂载）、后整列覆盖入库（D5 事务序）
        InOrder materializeFirst = org.mockito.Mockito.inOrder(sessionMountMaterializer, sessionRepository);
        materializeFirst.verify(sessionMountMaterializer).materialize(any(AgentSession.class), argThat(list ->
                list.size() == 1 && "file_2".equals(list.get(0).fileId())));
        materializeFirst.verify(sessionRepository).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwSessionNotFound_when_appendResources_given_missingSession() {
        // given（会话不存在：统一 session_not_found → 404 not_found_error，资源零变更）
        when(sessionRepository.findBySessionId("s-missing")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> sessionLifecycleService.appendResources("s-missing",
                List.of(SessionResource.file("file_1", null))));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwSessionNotFound_when_appendResources_given_otherUserSession() {
        // given（越权与不存在不可区分：他人会话同样 session_not_found → 404）
        AgentSession foreign = AgentSession.create("2", "agent-a", "1.0.0", "{}", null);
        when(sessionRepository.findBySessionId(foreign.sessionId())).thenReturn(Optional.of(foreign));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> sessionLifecycleService.appendResources(foreign.sessionId(),
                List.of(SessionResource.file("file_1", null))));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwSessionBusy_when_appendResources_given_archivedSession() {
        // given（已归档会话拒绝追加挂载 → 409 invalid_request_error：会话资源冲突特例）
        AgentSession archived = archivedSession();
        when(sessionRepository.findBySessionId(archived.sessionId())).thenReturn(Optional.of(archived));

        // when & then
        assertThrows(SessionBusyException.class, () -> sessionLifecycleService.appendResources(archived.sessionId(),
                List.of(SessionResource.file("file_1", null))));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwIllegalArgument_when_appendResources_given_emptyBatch() {
        // given（空批次 → 400：非空守卫在应用服务方法体首行，先于会话归属查询，故无需任何会话桩）

        // when & then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sessionLifecycleService.appendResources("sess_absent", List.of()));
        assertEquals("追加挂载资源不能为空", ex.getMessage());
        // then（会话仓储零触碰：载荷类 400 不会被会话不存在（404 语义）遮蔽）
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void should_throwIllegalArgument_when_appendResources_given_nonFileType() {
        // given（本期追加路径仅接受 file：memory_store → 400）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> sessionLifecycleService.appendResources(session.sessionId(),
                List.of(SessionResource.memoryStore("ms_1", null, null))));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwSessionBusy_when_appendResources_given_duplicateWithExistingMount() {
        // given（file_1 已挂载，再挂同一文件 → 409 invalid_request_error，契约明文会话资源冲突）
        AgentSession session = AgentSession.createWithTrigger("1", "agent-a", "1.0.0", "{}", null, null, null,
                List.of(SessionResource.file("file_1", null)));
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(SessionBusyException.class, () -> sessionLifecycleService.appendResources(session.sessionId(),
                List.of(SessionResource.file("file_1", "mounts/other.txt"))));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwSessionBusy_when_appendResources_given_duplicateWithinBatch() {
        // given（批内重复 fileId → 409 invalid_request_error，整批全有或全无）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(fileApi.findReadyMountMeta("file_2", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_2", "b.txt", 1024L)));

        // when & then
        assertThrows(SessionBusyException.class, () -> sessionLifecycleService.appendResources(session.sessionId(),
                List.of(SessionResource.file("file_2", null), SessionResource.file("file_2", "mounts/x.txt"))));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwSessionBusy_when_appendResources_given_mountPathCollidesWithExisting() {
        // given（file_1 已占缺省路径 mounts/file_1，追加不同文件但路径撞车 → 409 invalid_request_error，整批零变更）
        AgentSession session = AgentSession.createWithTrigger("1", "agent-a", "1.0.0", "{}", null, null, null,
                List.of(SessionResource.file("file_1", null)));
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then（字节清单整批材料化后由聚合按「判重 → 就绪 → 配额」顺序判定：路径占用胜出
        // ——file_2 未桩即清单缺键，若不就绪判定先于判重则冲突消息含「尚未就绪」；零物化零落库）
        SessionBusyException ex = assertThrows(SessionBusyException.class,
                () -> sessionLifecycleService.appendResources(session.sessionId(),
                        List.of(SessionResource.file("file_2", "mounts/file_1"))));
        assertTrue(ex.getMessage().contains("挂载路径已被占用"));
        verify(sessionMountMaterializer, never()).materialize(any(AgentSession.class), anyList());
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwSessionBusy_when_appendResources_given_fileNotReady() {
        // given（目标文件不存在 / 越权 / 未就绪：挂载元信息解析为空 → 409 invalid_request_error）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(fileApi.findReadyMountMeta("file_ghost", 1L)).thenReturn(Optional.empty());

        // when & then
        assertThrows(SessionBusyException.class, () -> sessionLifecycleService.appendResources(session.sessionId(),
                List.of(SessionResource.file("file_ghost", null))));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwIllegalArgument_when_appendResources_given_totalBytesExceedLimit() {
        // given（既有挂载 400MB + 追加 150MB → 超 500MB 上限 → 400，零变更）
        AgentSession session = AgentSession.createWithTrigger("1", "agent-a", "1.0.0", "{}", null, null, null,
                List.of(SessionResource.file("file_1", null)));
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        when(fileApi.findReadyMountMeta("file_1", 1L)).thenReturn(Optional.of(
                new FileMountMetaDTO("file_1", "big.bin", 400L * 1024 * 1024)));
        when(fileApi.findReadyMountMeta("file_2", 1L)).thenReturn(Optional.of(
                new FileMountMetaDTO("file_2", "bigger.bin", 150L * 1024 * 1024)));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> sessionLifecycleService.appendResources(session.sessionId(),
                List.of(SessionResource.file("file_2", null))));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_releaseAppendedCopies_when_appendResources_given_updateFails() {
        // given（物化成功但入库失败：仅删本轮新增副本，既有挂载副本不受影响）
        AgentSession session = AgentSession.createWithTrigger("1", "agent-a", "1.0.0", "{}", null, null, null,
                List.of(SessionResource.file("file_1", null)));
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(fileApi.findReadyMountMeta("file_1", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_1", "a.txt", 1024L)));
        when(fileApi.findReadyMountMeta("file_2", 1L))
                .thenReturn(Optional.of(new FileMountMetaDTO("file_2", "b.txt", 2048L)));
        doThrow(new RuntimeException("db down")).when(sessionRepository).updateResources(anyString(), anyList());

        // when & then
        assertThrows(RuntimeException.class, () -> sessionLifecycleService.appendResources(session.sessionId(),
                List.of(SessionResource.file("file_2", null))));
        verify(sessionMountMaterializer).release(eq(session), argThat(resource ->
                "file_2".equals(resource.fileId())));
        verify(sessionMountMaterializer, never()).releaseAll(any(AgentSession.class));
    }

    // ==================== 挂载资源管理（轮换 / 移除） ====================

    @Test
    void should_rotateTokenPreservingIdentity_when_rotateResourceToken_given_githubResource() {
        // given（github 挂载：轮换保留资源 ID 与 url/checkout，仅换令牌）
        SessionResource githubMount = SessionResource.githubRepository("https://github.com/o/r", "old-token", "main");
        AgentSession session = mountedSession(List.of(githubMount), List.of(), AgentSessionStatus.IDLE);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();

        // when
        SessionResource rotated = sessionLifecycleService.rotateResourceToken(session.sessionId(), githubMount.id(), "new-token");

        // then（返回资源携带新令牌；仓储收到整列覆盖）
        assertEquals(githubMount.id(), rotated.id());
        assertEquals("new-token", rotated.authorizationToken());
        assertEquals("https://github.com/o/r", rotated.url());
        assertEquals("main", rotated.checkout());
        verify(sessionRepository).updateResources(eq(session.sessionId()), argThat(list ->
                list.size() == 1 && "new-token".equals(list.get(0).authorizationToken())));
    }

    @Test
    void should_throwBadRequest_when_rotateResourceToken_given_blankToken() {
        // given（空令牌轮换拒绝，避免清空既有凭证）
        SessionResource githubMount = SessionResource.githubRepository("https://github.com/o/r", "old", null);
        AgentSession session = mountedSession(List.of(githubMount), List.of(), AgentSessionStatus.IDLE);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> sessionLifecycleService.rotateResourceToken(session.sessionId(), githubMount.id(), " "));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwBadRequest_when_rotateResourceToken_given_fileResource() {
        // given（非 github_repository 资源类型门禁）
        SessionResource fileMount = SessionResource.file("file_1", null);
        AgentSession session = mountedSession(List.of(fileMount), List.of(), AgentSessionStatus.IDLE);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> sessionLifecycleService.rotateResourceToken(session.sessionId(), fileMount.id(), "new"));
    }

    @Test
    void should_throwNotFound_when_rotateResourceToken_given_unmountedResourceId() {
        // given
        AgentSession session = mountedSession(List.of(SessionResource.file("file_1", null)),
                List.of(), AgentSessionStatus.IDLE);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(ResourceNotFoundException.class,
                () -> sessionLifecycleService.rotateResourceToken(session.sessionId(), "sesr_gone", "new"));
    }

    @Test
    void should_removeFileMount_when_removeResource_given_fileResourceId() {
        // given（file + github 混合挂载：移除 file 后 github 原样保留）
        SessionResource fileMount = SessionResource.file("file_1", null);
        SessionResource githubMount = SessionResource.githubRepository("https://github.com/o/r", "tok", null);
        AgentSession session = mountedSession(List.of(fileMount, githubMount), List.of(), AgentSessionStatus.IDLE);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();

        // when
        sessionLifecycleService.removeResource(session.sessionId(), fileMount.id());

        // then（整列覆盖：仅剩 github；file 项移除即释放宿主副本）
        verify(sessionRepository).updateResources(eq(session.sessionId()), argThat(list ->
                list.size() == 1 && githubMount.id().equals(list.get(0).id())));
        verify(sessionMountMaterializer).release(any(AgentSession.class), argThat(resource ->
                "file_1".equals(resource.fileId())));
    }

    @Test
    void should_throwSessionBusy_when_removeResource_given_githubResource() {
        // given（github_repository / memory_store 挂载后不可摘除 → 409 invalid_request_error，会话资源冲突）
        SessionResource memoryMount = SessionResource.memoryStore("ms_1", "read_only", null);
        AgentSession session = mountedSession(List.of(memoryMount), List.of("ms_1"), AgentSessionStatus.IDLE);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(SessionBusyException.class,
                () -> sessionLifecycleService.removeResource(session.sessionId(), memoryMount.id()));
        verify(sessionRepository, never()).updateResources(anyString(), anyList());
    }

    @Test
    void should_throwSessionBusy_when_removeResource_given_archivedSession() {
        // given（归档会话挂载不可变更，门禁先于资源定位；409 invalid_request_error 会话资源冲突特例）
        AgentSession session = archivedSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));

        // when & then
        assertThrows(SessionBusyException.class,
                () -> sessionLifecycleService.removeResource(session.sessionId(), "sesr_any"));
    }

    @Test
    void should_not_place_interrupt_signal_when_interruptSession_given_no_active_turn() {
        // given：会话 idle（无活跃执行、无 HITL 现场），MARK_CANCELING 迁移 CAS 0 行（取消空操作）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(sessionRepository.transition(session.sessionId(), Transition.PHASE_CANCEL)).thenReturn(0);
        // HITL 作废通道（waiting_confirmation → idle）同样经 transition 发起：会话非等待态时 CAS 0 行，取消链继续向下判定
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(0);

        // when
        sessionLifecycleService.interruptSession(session.sessionId());

        // then：CAS 0 行 → 直接幂等返回，不投递任何取消信号、无任何协调层写入、无会话状态事件落库
        //       （空取消不产生残留信号：既无进程内中断、也无 canceling 痕迹，更不进协调层）
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_CANCEL);
        verifyNoInteractions(coordinationLeaseService);
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_interrupt_in_process_when_interruptSession_given_canceling_committed() {
        // given：一轮活跃执行中（beginTurn 置入控制面 + beginRound 置入累积态），取消在流处理期间到达；
        //        MARK_CANCELING 迁移 CAS 命中 1 行（回归固化断言前置：本变更不重排踢流时机，踢流本就在事务提交之后）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        BuiltAgent agent = wireHappyPathForExecution();
        bindConnection(session);
        when(sessionRepository.transition(session.sessionId(), Transition.PHASE_CANCEL)).thenReturn(1);
        // HITL 作废通道（waiting_confirmation → idle）同样经 transition 发起：会话非等待态时 CAS 0 行，取消链继续向下判定
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(0);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Flux.just(AgentStreamSignal.of(AgentStreamSignalType.TEXT_DELTA, "你好", "blk-1"))
                        .doOnNext(ignored -> sessionLifecycleService.interruptSession(session.sessionId())));

        // when
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));

        // then：canceling 持久条件更新提交（MARK_CANCELING 迁移）先于同实例进程内踢流（interruptCurrentRun →
        //        turn.cancel → agent.interrupt）；防未来把踢流提前到事务之内（本变更保持既有顺序不变）
        InOrder inOrder = org.mockito.Mockito.inOrder(sessionRepository, agent);
        inOrder.verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_CANCEL);
        inOrder.verify(agent).interrupt(session.userId(), session.sessionId());
    }

    @Test
    void should_returnToIdle_when_interruptSession_given_waitingConfirmation() {
        // given：HITL 等待期间中断（durable 等待：无流可中断，作废等待直接回 idle，不经 canceling）
        // 注（D19）：等待须由「批次 id 命中已登记候选」合法确立——TOOL_CALL_END 登记 tc-1 候选，
        // REQUIRE 批次携带 tc-1，enterWaitingConfirm 据完整信号迁入等待相位（等待事实＝未应答工具调用事件表行）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_START, "tc-1", "search", null, null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_DELTA, "tc-1", "search",
                                "{\"q\":\"x\"}", null),
                        AgentStreamSignal.tool(AgentStreamSignalType.TOOL_CALL_END, "tc-1", "search", null, null),
                        AgentStreamSignal.hitl(AgentStreamSignalType.HUMAN_CONFIRM_REQUIRED, "reply-1",
                                List.of("tc-1")),
                        AgentStreamSignal.of(AgentStreamSignalType.AGENT_END, null, null)));
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(1);

        // when：同步执行器挂起收轮进入等待确认态后提交 user.interrupt
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "你好"));
        // 前置：应已挂起进入等待确认态（durable 挂起即物理轮终局）——相位迁入等待 + 未应答工具调用入事件表
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_AWAIT);
        assertTrue(savedChatEvents().stream().anyMatch(e -> e.type() == ChatEventType.AGENT_TOOL_USE),
                "前置：挂起现场须有未应答的工具调用事件表行");
        sessionLifecycleService.interruptSession(session.sessionId());

        // then：durable 作废（ABANDON_WAITING_CONFIRMATION CAS）+ 收场二事件留痕，不续跑不经 canceling
        verify(sessionRepository).transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION);
        verify(sessionRepository, never()).transition(anyString(), eq(Transition.PHASE_CANCEL));
        verify(agentRunExecutor, never()).resumeConfirmation(any(BuiltAgent.class), anyList(),
                anyString(), anyString(), anyBoolean(), any());
        List<ChatEvent> saved = savedChatEvents();
        assertTrue(saved.stream().anyMatch(e -> e.type() == ChatEventType.SESSION_THREAD_STATUS_IDLE
                        && e.payload().contains("\"type\":\"interrupted\"")),
                "中断语义经线程收场事件 stop_reason=interrupted 表达（无 cancelled 终态）");
        assertTrue(saved.stream().anyMatch(e -> e.type() == ChatEventType.SESSION_STATUS_IDLE
                        && e.payload().contains("\"type\":\"interrupted\"")),
                "作废后状态回 idle 且 stop_reason=interrupted 留痕");
    }

    @Test
    void should_markCancelingSilently_when_interruptSession_given_activeExecution() {
        // given（会话 running 有活跃执行：取消置相位 running→cancelling，对外 status 恒为 running 不改写）
        AgentSession session = idleSession().withStatus(AgentSessionStatus.RUNNING);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        bindConnection(session);
        when(sessionRepository.transition(session.sessionId(), Transition.PHASE_CANCEL)).thenReturn(1);
        // HITL 作废通道（waiting_confirmation → idle）同样经 transition 发起：会话非等待态时 CAS 0 行，取消链继续向下判定
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(0);

        // when
        sessionLifecycleService.interruptSession(session.sessionId());

        // then（CAS 命中即取消请求生效，且取消中段不落任何状态事件、不广播、不经 durable 作废通道；
        //       收束回 idle 由执行侧中断终态出口闭环，不在本入口产生 cancelled 终态）
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_CANCEL);
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
        verify(connectionHandle, never()).push(any(ChatEvent.class));
        verify(applicationEventPublisher, never()).publishEvent(any(TurnFinished.class));
    }

    @Test
    void should_beNoopAndPersistNothing_when_interruptSession_given_idleSessionWithoutActiveExecution() {
        // given（idle 无活跃执行：取消为空操作、幂等，CAS 0 行不产生状态事件）
        AgentSession session = idleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(sessionRepository.transition(session.sessionId(), Transition.PHASE_CANCEL)).thenReturn(0);
        // HITL 作废通道（waiting_confirmation → idle）同样经 transition 发起：会话非等待态时 CAS 0 行，取消链继续向下判定
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(0);

        // when
        sessionLifecycleService.interruptSession(session.sessionId());

        // then（CAS 0 行直接幂等返回：不投递取消信号、不进协调层，无 session.status_canceling 事件落库）
        verifyNoInteractions(coordinationLeaseService);
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_CANCEL);
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_beIdempotentNoSideEffect_when_interruptSession_given_cancelingSession() {
        // given（会话已处于取消中相位 cancelling：重复取消 CAS 0 行，幂等无副作用）
        AgentSession session = idleSession().withStatus(AgentSessionStatus.RUNNING).withPhase(TurnPhase.CANCELLING);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(sessionRepository.transition(session.sessionId(), Transition.PHASE_CANCEL)).thenReturn(0);
        // HITL 作废通道（waiting_confirmation → idle）同样经 transition 发起：会话非等待态时 CAS 0 行，取消链继续向下判定
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(0);

        // when
        sessionLifecycleService.interruptSession(session.sessionId());

        // then（不投递取消信号、不进协调层，不重复落 session.status_canceling 事件）
        verifyNoInteractions(coordinationLeaseService);
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_CANCEL);
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_notReviveOrPersist_when_interruptSession_given_terminatedSession() {
        // given（已终止会话收到取消：CAS 0 行，不产生状态事件、不复活会话）
        AgentSession session = idleSession().withStatus(AgentSessionStatus.TERMINATED);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        when(sessionRepository.transition(session.sessionId(), Transition.PHASE_CANCEL)).thenReturn(0);
        // HITL 作废通道（waiting_confirmation → idle）同样经 transition 发起：会话非等待态时 CAS 0 行，取消链继续向下判定
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(0);

        // when
        sessionLifecycleService.interruptSession(session.sessionId());

        // then
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_CANCEL);
        verify(chatEventRepository, never()).save(any(ChatEvent.class));
    }

    @Test
    void should_publishNothingOnSuspendAndTerminatedOnInvalidate_when_interruptSession_given_scheduledSessionWaitingConfirmation() {
        // given：调度会话真实挂起（durable 挂起即物理轮终局）
        AgentSession session = scheduledIdleSession();
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireHappyPathForExecution();
        bindConnection(session);
        when(agentRunExecutor.streamEvents(any(BuiltAgent.class), anyString(), anyString(), anyString()))
                .thenReturn(suspendBatchSignals());

        // when：挂起收轮
        turnExecutionService.sendMessageAsync(new SendMessageCommand(session.sessionId(), "执行"));

        // then：挂起驻留不是运行终局——只做相位 CAS + 未应答工具调用入事件表，零终局事件
        verify(sessionRepository).transition(session.sessionId(), Transition.PHASE_AWAIT);
        assertTrue(savedChatEvents().stream().anyMatch(e -> e.type() == ChatEventType.AGENT_TOOL_USE),
                "挂起现场须有未应答的工具调用事件表行");
        verify(applicationEventPublisher, never()).publishEvent(any(TurnFinished.class));

        // when：等待期间中断作废（waiting → idle CAS 命中）
        when(sessionRepository.transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION)).thenReturn(1);
        sessionLifecycleService.interruptSession(session.sessionId());

        // then：挂起作废通道按中断收口，且此前驻留未发事件 → 全程仅一次
        verify(sessionRepository).transition(session.sessionId(), Transition.ABANDON_WAITING_CONFIRMATION);
        verify(applicationEventPublisher, times(1))
                .publishEvent(new TurnFinished(session.sessionId(), "cron", "terminated"));
    }

    @Test
    void should_publishTerminatedTurnFinished_when_deleteSession_given_scheduledWaitingConfirmationSession() {
        // given：等待确认中的调度会话被删除（挂起轮次随之作废，不经终态唯一出口）
        AgentSession session = scheduledIdleSession().withPhase(TurnPhase.AWAITING_CONFIRMATION);
        when(sessionRepository.findBySessionId(session.sessionId())).thenReturn(Optional.of(session));
        wireTransactionTemplate();
        bindConnection(session);

        // when
        sessionLifecycleService.deleteSession(session.sessionId());

        // then：挂起态删除通道按中断收口一次（终局事件带调度打标）
        verify(sessionRepository).deleteBySessionId(session.sessionId());
        verify(applicationEventPublisher, times(1))
                .publishEvent(new TurnFinished(session.sessionId(), "cron", "terminated"));
    }

}
