package com.linkroa.deepdataagent.runtime.application.service.assembly;

import com.linkroa.deepdataagent.agent.api.dto.EnvironmentReferenceDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedModelCredentialDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeAgentAssemblyService} 运行时装配单测。
 * <p>验证装配完全来自 Agent 台账与 Session 挂载（环境变量），
 * 缓存键含挂载签名（跨挂载不共享规格）；模型 / maxIters / system 取解析结果
 * 而非 {@code app.agent} 全局配置；沙箱资源恒取全局运行时属性。file 挂载走缓存未命中路径
 * reconcile 对账并带入视图（D20 单一事实源）、挂载变化即时失效缓存且缓存命中不重复对账
 * （sandbox-workspace-file-mounts D14 / D20）。</p>
 * <p><b>装配缓存改造（slim-agent-assembly R14 / D2）</b>：缓存值为无凭证明文快照，
 * 明文凭据一律不进缓存（反射读取缓存内容断言无 {@code sk-} 明文）；命中路径每轮经
 * {@code resolveModelCredential} 实时注入模型凭据（verify 次数 = 每命中轮一次，且全量
 * {@code resolve} 不因命中而重复）；跨会话同 agent + 版本互不串号。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuntimeAgentAssemblyServiceTest {

    @Mock private AgentVersionAssemblyPort agentVersionAssemblyPort;
    @Mock private SessionMountMaterializer sessionMountMaterializer;

    /** 运行时全局配置：直注真实配置对象（配置类无逻辑，无需 mock） */
    private AgentRuntimeProperties runtimeProperties;

    private RuntimeAgentAssemblyService resolver;

    @BeforeEach
    void setUp() {
        resolver = new RuntimeAgentAssemblyService();
        ReflectionTestUtils.setField(resolver, "agentVersionAssemblyPort", agentVersionAssemblyPort);
        // 沙箱规格三值直读全局配置
        runtimeProperties = new AgentRuntimeProperties();
        runtimeProperties.setSandboxImage("python:3.12");
        runtimeProperties.setSandboxMemoryBytes(8192L);
        runtimeProperties.setSandboxCpuCount(4L);
        ReflectionTestUtils.setField(resolver, "runtimeProperties", runtimeProperties);
        ReflectionTestUtils.setField(resolver, "sessionMountMaterializer", sessionMountMaterializer);
        ReflectionTestUtils.setField(resolver, "objectMapper", new ObjectMapper());
    }

    @Test
    void should_assembleSpec_when_assemble_given_session() {
        // given（模型/系统提示词/迭代上限仅来自台账，全局装配配置已移除不存在回退）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L))
                .thenReturn(new ResolvedAgentAssemblyDTO(
                        "agent-a", 1, "v1", "台账系统提示词", "# 项目约定\n\n统一使用中文回复。",
                        "openai:gpt-4", 10, "sk-plain",
                        "https://api.example.com/v1", List.of(), null));
        AgentSession session = AgentSession.create("1", "agent-a", "1", "{}", null);

        // when
        AgentAssemblySpec spec = resolver.assemble(session);

        // then（model/system/maxIters 完全来自解析台账）
        assertEquals("agent-a", spec.agentId());
        assertEquals("v1", spec.name());
        assertEquals("openai:gpt-4", spec.model());
        assertEquals("台账系统提示词", spec.systemPrompt());
        // AGENTS.md 随契约进入规格，供工厂物化到 Agent 工作区根
        assertEquals("# 项目约定\n\n统一使用中文回复。", spec.agentsMd());
        assertEquals(10, spec.maxIters());
        // 沙箱等运行时基础设施参数仍取 app.agent 配置
        assertEquals("python:3.12", spec.sandbox().image());
        assertEquals(8192L, spec.sandbox().memoryBytes());
        assertEquals(4L, spec.sandbox().cpuCount());
        // 凭证/API 端点一并承载于装配规格
        assertEquals("sk-plain", spec.credential());
        assertEquals("https://api.example.com/v1", spec.apiEndpointUrl());
        assertTrue(spec.skills().isEmpty());
        assertTrue(spec.environmentVariables().isEmpty());
    }

    @Test
    void should_useGlobalSandbox_when_assemble_given_versionWithEnvironmentReference() {
        // given（版本快照携带环境引用：契约不再承载沙箱明细，沙箱资源恒取全局运行时属性）
        EnvironmentReferenceDTO environment = new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "cloud", "pip install pandas");
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L))
                .thenReturn(new ResolvedAgentAssemblyDTO(
                        "agent-a", 1, "v1", "台账系统提示词", "# 项目约定\n\n统一使用中文回复。",
                        "openai:gpt-4", 10, "sk-plain",
                        "https://api.example.com/v1", List.of(), environment));
        AgentSession session = AgentSession.create("1", "agent-a", "1", "{}", null);

        // when
        AgentAssemblySpec spec = resolver.assemble(session);

        // then（沙箱取全局默认）
        assertEquals("python:3.12", spec.sandbox().image());
        assertEquals(8192L, spec.sandbox().memoryBytes());
        assertEquals(4L, spec.sandbox().cpuCount());
    }

    @Test
    void should_parseEnvVars_when_assemble_given_sessionEnvironmentVariables() {
        // given（会话级环境变量 JSON 文本解析为键值映射，供工厂注入沙箱数据面）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(snapshotDto(null));
        AgentSession session = AgentSession.createWithMounts("1", "agent-a", "1", "{}", null,
                null, null, List.of(), null, "{\"LOG_LEVEL\":\"debug\",\"TZ\":\"Asia/Shanghai\"}");

        // when
        AgentAssemblySpec spec = resolver.assemble(session);

        // then
        assertEquals(Map.of("LOG_LEVEL", "debug", "TZ", "Asia/Shanghai"), spec.environmentVariables());
    }

    @Test
    void should_reject_when_assemble_given_malformedEnvironmentVariables() {
        // given（非法 JSON 文本：装配拒绝而非静默丢弃挂载）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(snapshotDto(null));
        AgentSession session = AgentSession.createWithMounts("1", "agent-a", "1", "{}", null,
                null, null, List.of(), null, "not-a-json");

        // when & then
        assertThrows(IllegalArgumentException.class, () -> resolver.assemble(session));
    }

    @Test
    void should_notShareSpecAcrossMounts_when_assemble_given_sameAgentVersionDifferentMounts() {
        // given（同一 agent + 版本、不同环境挂载：缓存键含挂载签名，禁止跨挂载复用规格；
        //       装配期不再复检环境类型，隔离仅由 mountSignature 承担（D16））
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(snapshotDto(null));
        AgentSession sessionA = AgentSession.createWithMounts("1", "agent-a", "1", "{}", null,
                null, null, List.of(), "env-a", null);
        AgentSession sessionB = AgentSession.createWithMounts("1", "agent-a", "1", "{}", null,
                null, null, List.of(), "env-b", null);

        // when
        AgentAssemblySpec specA = resolver.assemble(sessionA);
        AgentAssemblySpec specB = resolver.assemble(sessionB);

        // then（两次装配签名不同 → 各自解析台账，互不共享缓存规格；不查询环境类型）
        assertEquals("python:3.12", specA.sandbox().image());
        assertEquals("python:3.12", specB.sandbox().image());
        verify(agentVersionAssemblyPort, times(2)).resolve("agent-a", "1", 1L);
    }

    @Test
    void should_carryReconciledView_when_assemble_given_fileMountedSession() {
        // given（file 挂载：缓存未命中路径经 reconcile 对账，产出视图原样带入装配规格，D20 单一事实源）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(snapshotDto(null));
        List<AgentAssemblySpec.FileMountRef> view = List.of(
                new AgentAssemblySpec.FileMountRef("file_1", "sales.csv", 2048L, "mounts/file_1"));
        AgentSession session = AgentSession.createWithMounts("1", "agent-a", "1", "{}", null,
                null, null, List.of(SessionResource.file("file_1", null)), null, null);
        when(sessionMountMaterializer.reconcile(session, 1L)).thenReturn(view);

        // when
        AgentAssemblySpec spec = resolver.assemble(session);

        // then（清单与 bind mount 同源于对账视图，非 session.resources 裸映射）
        assertEquals(view, spec.fileMounts());
        verify(sessionMountMaterializer).reconcile(session, 1L);
    }

    @Test
    void should_invalidateCache_when_assemble_given_fileMountAppendedToSameSession() {
        // given（同一会话追加 file 挂载：file 项入挂载签名是正确性要求，TTL 内 MUST NOT 命中旧规格，D14）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(snapshotDto(null));
        AgentSession before = AgentSession.create("1", "agent-a", "1", "{}", null);
        AgentSession after = before.withAppendedResources(List.of(SessionResource.file("file_1", null)));

        // when（追加前后各装配一次）
        resolver.assemble(before);
        resolver.assemble(after);

        // then（签名变化 → 缓存失效，重新解析契约并再次对账）
        verify(agentVersionAssemblyPort, times(2)).resolve("agent-a", "1", 1L);
        verify(sessionMountMaterializer, times(2)).reconcile(any(), any());
    }

    @Test
    void should_skipReconcile_when_assemble_given_cacheHitWithinTtl() {
        // given（同一会话多轮 turn：签名一致，缓存命中路径不触发对账物化，仅实时注入凭据段）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(snapshotDto(null));
        when(agentVersionAssemblyPort.resolveModelCredential("agent-a", "1", 1L))
                .thenReturn(new ResolvedModelCredentialDTO(null, "https://api.example.com/v1"));
        AgentSession session = AgentSession.create("1", "agent-a", "1", "{}", null);

        // when（连续两轮装配）
        resolver.assemble(session);
        resolver.assemble(session);

        // then（对账仅发生在缓存未命中路径；命中路径不重复全量 resolve）
        verify(sessionMountMaterializer, times(1)).reconcile(session, 1L);
        verify(agentVersionAssemblyPort, times(1)).resolve("agent-a", "1", 1L);
    }

    @Test
    void should_delegateValidation_when_assertResolvable_given_agentIdAndVersion() {
        // given（轻量校验：会话创建前置校验链，仅委托端口（含 owner），不触发装配解析与凭证解密）

        // when
        resolver.assertResolvable("agent-a", "2", 1L);

        // then（校验失败向上传播 404，端口被正确委托）
        verify(agentVersionAssemblyPort).assertResolvable("agent-a", "2", 1L);
    }

    @Test
    void should_delegateActiveVersion_when_activeVersionNumber_given_agentId() {
        // given
        when(agentVersionAssemblyPort.activeVersionNumber("agent-a", 1L)).thenReturn("2");

        // when
        String version = resolver.activeVersionNumber("agent-a", 1L);

        // then
        assertEquals("2", version);
        verify(agentVersionAssemblyPort).activeVersionNumber("agent-a", 1L);
    }

    @Test
    void should_reuseCachedAssembly_when_assemble_given_sameSessionTwice() {
        // given（同一会话多轮 turn：挂载签名一致，无凭证快照命中，全量装配端口仅解析一次）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(snapshotDto(null));
        when(agentVersionAssemblyPort.resolveModelCredential("agent-a", "1", 1L))
                .thenReturn(new ResolvedModelCredentialDTO(null, "https://api.example.com/v1"));
        AgentSession session = AgentSession.create("1", "agent-a", "1", "{}", null);

        // when（连续两轮装配）
        resolver.assemble(session);
        AgentAssemblySpec spec = resolver.assemble(session);

        // then（缓存命中：全量装配端口仅被调用一次，产出归属仍来自快照）
        assertEquals("agent-a", spec.agentId());
        assertEquals(session.sessionId(), spec.sessionId());
        verify(agentVersionAssemblyPort, times(1)).resolve("agent-a", "1", 1L);
    }

    @Test
    void should_injectFreshModelCredentialPerTurn_when_assemble_given_cacheHitWithinTtl() {
        // given（首轮 miss 走全量 resolve，次轮起命中快照仅经窄端口材料化凭据段）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(credentialBearingDto("sk-plain"));
        when(agentVersionAssemblyPort.resolveModelCredential("agent-a", "1", 1L))
                .thenReturn(new ResolvedModelCredentialDTO("sk-plain", "https://api.example.com/v1"));
        AgentSession session = AgentSession.create("1", "agent-a", "1", "{}", null);

        // when（连续三轮装配）
        AgentAssemblySpec first = resolver.assemble(session);
        resolver.assemble(session);
        AgentAssemblySpec third = resolver.assemble(session);

        // then（全量 resolve 仅一次；命中两轮各注入一次凭据段；返回 spec 凭据非空且来自窄端口）
        assertEquals("sk-plain", first.credential());
        assertEquals("sk-plain", third.credential());
        assertEquals("https://api.example.com/v1", third.apiEndpointUrl());
        verify(agentVersionAssemblyPort, times(1)).resolve("agent-a", "1", 1L);
        verify(agentVersionAssemblyPort, times(2)).resolveModelCredential("agent-a", "1", 1L);
    }

    @Test
    void should_notCachePlaintextCredential_when_assemble_given_secretCredential() throws Exception {
        // given（首轮 miss 装配：模型明文凭据不得进入无凭证快照）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "台账系统提示词", null, "openai:gpt-4",
                10, "sk-super-secret-token", "https://api.example.com/v1", List.of(), null));
        AgentSession session = AgentSession.create("1", "agent-a", "1", "{}", null);

        // when（单轮 miss 装配，缓存写入一条快照）
        AgentAssemblySpec spec = resolver.assemble(session);

        // then（spec 携明文供工厂注入数据面）
        assertEquals("sk-super-secret-token", spec.credential());
        // 快照类型 MUST NOT 声明任何明文凭据字段
        Class<?> snapshotType = Class.forName(RuntimeAgentAssemblyService.class.getName()
                + "$CachedAssemblySnapshot");
        for (Field field : snapshotType.getDeclaredFields()) {
            String name = field.getName();
            assertFalse("credential".equals(name) || "apiEndpointUrl".equals(name)
                            || "token".equals(name) || "vaultCredentials".equals(name),
                    "缓存快照不得携带明文凭据字段: " + name);
        }
        // 缓存内容序列化 MUST NOT 出现任何明文凭据
        Object cache = ReflectionTestUtils.getField(resolver, "assemblyCache");
        assertEquals(1, ((AssemblyLruCache<?, ?>) cache).size());
        String dump = String.valueOf(ReflectionTestUtils.getField(cache, "map"));
        assertFalse(dump.contains("sk-super-secret-token"), "模型明文凭证泄露进缓存: " + dump);
    }

    @Test
    void should_keepSessionScopedOwnershipAndMountPaths_when_assemble_given_twoSessionsSameAgentVersion() {
        // given（两个不同会话、同 agent + 版本 + owner：缓存键含 sessionId，归属与 file 视图 MUST NOT 串号）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(snapshotDto(null));
        when(agentVersionAssemblyPort.resolveModelCredential("agent-a", "1", 1L))
                .thenReturn(new ResolvedModelCredentialDTO(null, "https://api.example.com/v1"));
        AgentSession sessionA = AgentSession.createWithMounts("1", "agent-a", "1", "{}", null,
                null, null, List.of(SessionResource.file("file_a", null)), null, null);
        AgentSession sessionB = AgentSession.createWithMounts("1", "agent-a", "1", "{}", null,
                null, null, List.of(SessionResource.file("file_b", null)), null, null);
        when(sessionMountMaterializer.reconcile(sessionA, 1L)).thenReturn(List.of(
                new AgentAssemblySpec.FileMountRef("file_a", "a.csv", 10L, "mounts/file_a")));
        when(sessionMountMaterializer.reconcile(sessionB, 1L)).thenReturn(List.of(
                new AgentAssemblySpec.FileMountRef("file_b", "b.csv", 20L, "mounts/file_b")));

        // when（A miss → B miss → A 再次命中：命中路径 MUST NOT 读到 B 的会话级产出）
        AgentAssemblySpec a1 = resolver.assemble(sessionA);
        AgentAssemblySpec b1 = resolver.assemble(sessionB);
        AgentAssemblySpec a2 = resolver.assemble(sessionA);

        // then（各自 sessionId / ownerId / file 视图独立，A 命中仍携自身视图）
        assertEquals(sessionA.sessionId(), a1.sessionId());
        assertEquals(sessionB.sessionId(), b1.sessionId());
        assertNotEquals(a1.sessionId(), b1.sessionId());
        assertEquals(1L, a1.ownerId());
        assertEquals("file_a", a1.fileMounts().get(0).fileId());
        assertEquals("file_b", b1.fileMounts().get(0).fileId());
        assertEquals("file_a", a2.fileMounts().get(0).fileId());
        assertEquals(sessionA.sessionId(), a2.sessionId());
        // A、B 各 miss 一次全量 resolve；A 命中不重复对账（reconcile 仅两次）
        verify(agentVersionAssemblyPort, times(2)).resolve("agent-a", "1", 1L);
        verify(sessionMountMaterializer, times(2)).reconcile(any(), any());
    }

    @Test
    void should_returnEquivalentSpec_when_assemble_given_hitAfterMiss() {
        // given（同会话首轮 miss 与次轮 hit 的最终 spec 字段值 MUST 等价）
        when(agentVersionAssemblyPort.resolve("agent-a", "1", 1L)).thenReturn(credentialBearingDto("sk-plain"));
        when(agentVersionAssemblyPort.resolveModelCredential("agent-a", "1", 1L))
                .thenReturn(new ResolvedModelCredentialDTO("sk-plain", "https://api.example.com/v1"));
        AgentSession session = AgentSession.createWithMounts("1", "agent-a", "1", "{}", null,
                null, null, List.of(), null, "{\"K\":\"V\"}");

        // when
        AgentAssemblySpec miss = resolver.assemble(session);
        AgentAssemblySpec hit = resolver.assemble(session);

        // then（逐扁平访问器等价：去凭证入缓存 + 每轮注入不改变装配产出）
        assertEquals(miss.agentId(), hit.agentId());
        assertEquals(miss.name(), hit.name());
        assertEquals(miss.model(), hit.model());
        assertEquals(miss.credential(), hit.credential());
        assertEquals(miss.apiEndpointUrl(), hit.apiEndpointUrl());
        assertEquals(miss.modelEffort(), hit.modelEffort());
        assertEquals(miss.modelContextWindow(), hit.modelContextWindow());
        assertEquals(miss.systemPrompt(), hit.systemPrompt());
        assertEquals(miss.agentsMd(), hit.agentsMd());
        assertEquals(miss.maxIters(), hit.maxIters());
        assertEquals(miss.sandbox(), hit.sandbox());
        assertEquals(miss.skills(), hit.skills());
        assertEquals(miss.environmentVariables(), hit.environmentVariables());
        assertEquals(miss.fileMounts(), hit.fileMounts());
        assertEquals(miss.toolPolicies(), hit.toolPolicies());
        assertEquals(miss.toolVisibility(), hit.toolVisibility());
        assertEquals(miss.sessionId(), hit.sessionId());
        assertEquals(miss.ownerId(), hit.ownerId());
    }

    /** 基准版本快照契约：仅调环境槽位，其余固定。 */
    private ResolvedAgentAssemblyDTO snapshotDto(EnvironmentReferenceDTO environment) {
        return new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "台账系统提示词", null, "openai:gpt-4",
                10, null, "https://api.example.com/v1", List.of(), environment);
    }

    /** 携带指定模型明文凭据的版本快照契约（其余固定）。 */
    private ResolvedAgentAssemblyDTO credentialBearingDto(String credential) {
        return new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "台账系统提示词", null, "openai:gpt-4",
                10, credential, "https://api.example.com/v1", List.of(), null);
    }
}