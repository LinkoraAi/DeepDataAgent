package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.contract.SseEventEnvelope;
import com.linkroa.deepdataagent.runtime.application.convert.SseEventEnvelopeConvert;
import com.linkroa.deepdataagent.runtime.application.port.ChatEventPersister;
import com.linkroa.deepdataagent.runtime.application.service.execution.TurnEventWriter;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSessionContext;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.port.ConnectionHandle;
import com.linkroa.deepdataagent.runtime.infrastructure.sse.ChatEventCodec;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.shared.security.SecretMasker;
import com.linkroa.deepdataagent.vault.application.command.AddVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.command.CreateVaultCommand;
import com.linkroa.deepdataagent.vault.application.dto.ResolvedVaultCredentialDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialResolutionPort;
import com.linkroa.deepdataagent.vault.application.service.VaultApplicationService;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import io.agentscope.harness.agent.tools.ToolsConfig;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭据出网集成测试（align-qoder-vault-credential-capabilities tasks 2.1 / 2.2 / 2.3 / 2.6）：
 * 真连 PostgreSQL + Redis 的全量上下文，验证「保管库明文唯一出口是 MCP 请求头」在真实存储与
 * 真实协议转换下成立。
 *
 * <p><b>与离线断言的分工</b>：可离线验证的掩码形态、解析器匹配、降级判定与诊断脱敏已下沉为常跑单测
 * （{@code McpDiscoveryAssemblyTest} / {@code SecretMaskerTest} / {@code SignalHandlersTest} /
 * {@code AgentscopeHarnessAgentFactoryTest} / {@code RuntimeArchitectureTest}），本类只承担
 * <b>必须真实 PG / 真实网络</b>的部分——密文落库形态、真实跨 BC 解密材料化、真实事件表持久化与
 * 真实 SSE 协议编码（tasks 2.7 选项 a）。</p>
 *
 * <p><b>默认不执行</b>：{@code @Tag("integration")} 由 {@code core-backend/pom.xml} 的
 * {@code <excludedGroups>integration</excludedGroups>} 排除，需 CI 显式放行 integration 组
 * （{@code TEST_PG_URL} / {@code TEST_REDIS_URL} 齐备）。</p>
 *
 * <p><b>容器 {@code Config.Env} 级 inspect 的留待项</b>：任务 2.2 的「容器 inspect」需要 Docker
 * daemon 与真实轮次（沙箱由框架 {@code SandboxLifecycleMiddleware} 在调用期创建），本类只断言
 * 平台交给框架沙箱规格的环境变量映射——即容器环境变量的唯一来源；容器级 inspect 待具备 Docker
 * 环境的 CI 补齐（见 tasks.md 2.7 裁定记录）。</p>
 */
class VaultCredentialEgressIntegrationTest extends RedisFullContextTestSupport {

    /** 集成用例归属用户（仓储无 users 外键约束，仅作 owner 隔离键）。 */
    private static final long OWNER_ID = 990_001L;

    /** 唯一出口验证用凭证明文：形态不匹配 {@code sk-*} / {@code Bearer } 正则，只能靠精确掩码覆盖。 */
    private static final String PLAINTEXT = "ghp_integration-egress-token";

    /** 事件表断言用会话 ID（{@code chat_event} 无外键，可直接落行）。 */
    private static final String LEDGER_SESSION_ID = "sess_egress_it_ledger";

    /** 已知明文精确掩码的替换标记（与 {@link SecretMasker} 内部常量同形，供出站载荷断言）。 */
    private static final String EXACT_VALUE_MASK = "****";

    @Resource
    private VaultApplicationService vaultApplicationService;
    @Resource
    private VaultCredentialResolutionPort credentialResolutionPort;
    @Resource
    private ChatEventPersister chatEventPersister;
    @Resource
    private TurnEventWriter turnEventWriter;
    @Resource
    private AgentscopeHarnessAgentFactory agentFactory;

    /** 本用例创建的保管库业务 ID（全量上下文不随用例事务回滚，逐条物理清理）。 */
    private final List<String> createdVaultIds = new ArrayList<>();

    @Test
    void should_injectBearerHeaderOnly_when_callReadOnlyTool_given_credentialPersistedInPostgres()
            throws IOException {
        // given（需鉴权服务器；凭证经真实保管库应用服务加密落库，再经真实跨 BC 端口解密材料化）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            server.setRequiresBearer(true);
            AgentAssemblySpec.VaultCredentialRef credential = persistAndResolveCredential(server.url());
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())),
                    List.of(credential), Map.of());

            // when（装配期 discovery → 实名改名前置 → 模型按运行时实名调用只读工具）
            Discovery discovery = discover(spec);
            agentFactory.renameMcpTools(discovery.toolkit());
            ToolBase readOnly = (ToolBase) discovery.toolkit().getTool("mcp__weather__get_weather");
            ToolResultBlock result = readOnly.callAsync(ToolCallParam.builder()
                    .input(Map.of("city", "杭州")).build()).block();

            // then（鉴权真正生效：连接未降级、调用成功，且凭据只出现在请求头，请求体零明文）
            assertNotNull(result);
            assertEquals(McpServerRegistrationResult.Status.SUCCESS, discovery.onlyResult().status());
            List<FakeMcpServer.RecordedRequest> calls = server.requestsOf("tools/call");
            assertEquals(1, calls.size());
            assertEquals("get_weather", server.lastCalledToolName());
            assertEquals("Bearer " + PLAINTEXT, calls.get(0).header("Authorization"));
            assertFalse(calls.get(0).body().contains(PLAINTEXT), calls.get(0).body());
        }
    }

    @Test
    void should_keepSandboxEnvironmentFreeOfPlaintext_when_sandboxEnvironment_given_realDecryptedCredential()
            throws IOException {
        // given（真实 PG 解密出的明文凭据随会话挂载进入装配规格；会话环境变量为唯一合法载荷）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec.VaultCredentialRef credential = persistAndResolveCredential(server.url());
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())),
                    List.of(credential), Map.of("LOG_LEVEL", "info"));

            // when（取平台交给框架沙箱规格的环境变量映射——容器 Config.Env 的唯一来源）
            @SuppressWarnings("unchecked")
            Map<String, String> sandboxEnv = (Map<String, String>) ReflectionTestUtils.invokeMethod(
                    agentFactory, "sandboxEnvironment", spec);

            // then（沙箱数据面只有会话环境变量：键不出现保管库语义、值对明文精确匹配零命中）
            assertNotNull(sandboxEnv);
            assertEquals(Map.of("LOG_LEVEL", "info"), sandboxEnv);
            assertTrue(sandboxEnv.keySet().stream().noneMatch(key -> key.toUpperCase().contains("VAULT")),
                    sandboxEnv.toString());
            assertTrue(sandboxEnv.values().stream().noneMatch(value -> value.contains(PLAINTEXT)),
                    sandboxEnv.toString());
        }
    }

    @Test
    void should_keepLedgerAndSseFreeOfPlaintext_when_pushAndPersist_given_toolResultEchoingCredential() {
        // given（工具结果回显凭证明文，经既定掩码契约产出出站载荷——掩码本身由常跑单测覆盖）
        String maskedOutput = SecretMasker.maskExactValues(
                SecretMasker.maskSecrets("head 回显 " + PLAINTEXT + " tail"), List.of(PLAINTEXT));
        ChatEvent event = ChatEvent.create(LEDGER_SESSION_ID, ChatEventType.AGENT_MCP_TOOL_RESULT,
                "{\"tool_use_id\":\"tc-1\",\"output\":\"" + maskedOutput + "\"}",
                1L, ChatEvent.EVENT_ID_PREFIX + "integration_egress_ledger");

        // when①（真实 SSE 协议转换 + 编码：连接层实际下发的 JSON 载荷）
        SseEventEnvelope envelope = SseEventEnvelopeConvert.INSTANCE.toEnvelope(event);
        String sseJson = ChatEventCodec.toJson(envelope);

        // when②（真实写入器的连接层推送：经连接句柄捕获实际推送的事件）
        AgentSessionContext sessionContext = mock(AgentSessionContext.class);
        ConnectionHandle connection = mock(ConnectionHandle.class);
        when(sessionContext.connection()).thenReturn(connection);
        turnEventWriter.pushQuietly(sessionContext, event);
        ArgumentCaptor<ChatEvent> pushed = ArgumentCaptor.forClass(ChatEvent.class);
        verify(connection).push(pushed.capture());

        // when③（真实异步落库 + PostgreSQL 事件表读回）
        chatEventPersister.enqueue(event);
        chatEventPersister.flush();
        String ledgerPayload = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM chat_event WHERE session_id = ?", String.class, LEDGER_SESSION_ID);

        // then（SSE 载荷 / 推送事件 / 事件表行三处对明文精确匹配零命中，且掩码标记确已落盘）
        assertFalse(sseJson.contains(PLAINTEXT), sseJson);
        assertTrue(sseJson.contains(EXACT_VALUE_MASK), sseJson);
        assertFalse(pushed.getValue().payload().contains(PLAINTEXT), pushed.getValue().payload());
        assertNotNull(ledgerPayload);
        assertFalse(ledgerPayload.contains(PLAINTEXT), ledgerPayload);
        assertTrue(ledgerPayload.contains(EXACT_VALUE_MASK), ledgerPayload);
    }

    @Test
    void should_keepReachableConnectionUsable_when_discovery_given_unreachableServerAmongReachableOnes()
            throws IOException {
        // given（两个声明连接：weather 可达且命中本轮真实材料化凭证、dead 为必然拒绝连接的本地端口）
        try (FakeMcpServer reachable = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec.VaultCredentialRef credential = persistAndResolveCredential(reachable.url());
            AgentAssemblySpec spec = specWithMcp(List.of(
                    new AgentAssemblySpec.McpConnection("weather", reachable.url()),
                    new AgentAssemblySpec.McpConnection("dead", unreachableLocalUrl())),
                    List.of(credential), Map.of());

            // when（装配期 discovery：单连接不可达 MUST NOT 抛出、MUST NOT 阻断装配）
            Discovery discovery = discover(spec);

            // then（失败连接留下含服务器名与失败类别的诊断、工具不入清单；可达连接握手 / 枚举与
            //       鉴权头注入照常——即「单连接降级不影响其余」在真实网络下的收敛形态）
            assertEquals(2, discovery.results().size());
            McpServerRegistrationResult dead = resultOf(discovery, "dead");
            assertEquals(McpServerRegistrationResult.Status.FAILED, dead.status());
            assertNotNull(dead.cause());
            assertTrue(toolNamesOfServer(discovery.toolkit(), "dead").isEmpty());
            assertEquals(McpServerRegistrationResult.Status.SUCCESS, resultOf(discovery, "weather").status());
            assertFalse(reachable.requests().isEmpty());
            for (FakeMcpServer.RecordedRequest request : reachable.requests()) {
                assertEquals("Bearer " + PLAINTEXT, request.header("Authorization"),
                        "未携带鉴权头的请求: " + request.method());
            }
        }
    }

    /**
     * 经真实保管库应用服务落库一条 {@code static_bearer} 凭证，再经真实跨 BC 端口按 owner 解密材料化。
     *
     * <p>同时断言落库形态（BYTEA 密文对明文精确匹配零命中）——即「凭证只写不读、密文落库」在真实
     * 加密与真实存储下的形态；返回的引用携带内存明文，供装配规格注入 MCP 请求头。</p>
     */
    private AgentAssemblySpec.VaultCredentialRef persistAndResolveCredential(String mcpServerUrl) {
        // owner 由应用服务经 AuthContext 收敛（管理面入口语义）
        AuthContext.setUserId(OWNER_ID);
        try {
            Vault vault = vaultApplicationService.create(new CreateVaultCommand("凭据出网集成测试保管库", null));
            createdVaultIds.add(vault.vaultId());
            VaultCredential credential = vaultApplicationService.addCredential(
                    new AddVaultCredentialCommand(vault.vaultId(), "static_bearer", mcpServerUrl, PLAINTEXT));

            // 落库形态：密文列对明文零命中（明文未随任何持久化路径落盘）
            byte[] ciphertext = jdbcTemplate.queryForObject(
                    "SELECT ciphertext FROM vault_credentials WHERE credential_id = ?",
                    byte[].class, credential.credentialId());
            assertNotNull(ciphertext);
            assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains(PLAINTEXT));

            // 运行时材料化：真实跨 BC 端口（owner 隔离 + 排除归档 + AES-GCM 解密），明文仅内存持有
            List<ResolvedVaultCredentialDTO> resolved =
                    credentialResolutionPort.resolveVaultCredentials(OWNER_ID, List.of(vault.vaultId()));
            assertEquals(1, resolved.size());
            ResolvedVaultCredentialDTO only = resolved.get(0);
            assertEquals(PLAINTEXT, only.token());
            assertEquals(mcpServerUrl, only.mcpServerUrl());
            return new AgentAssemblySpec.VaultCredentialRef(
                    only.vaultId(), only.credentialId(), only.authType(), only.mcpServerUrl(), only.token());
        } finally {
            AuthContext.clear();
        }
    }

    /** 服务端暴露的工具：一个声明只读（{@code readOnlyHint: true}）、一个写操作。 */
    private static List<Map<String, Object>> weatherTools() {
        return List.of(
                tool("get_weather", "查询天气", true),
                tool("delete_city", "删除城市", false));
    }

    private static Map<String, Object> tool(String name, String description, boolean readOnlyHint) {
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "annotations", Map.of("readOnlyHint", readOnlyHint));
    }

    /** 携带 MCP 连接声明、挂载凭证与会话环境变量的装配规格（与离线断言基线一致）。 */
    private static AgentAssemblySpec specWithMcp(List<AgentAssemblySpec.McpConnection> connections,
                                                 List<AgentAssemblySpec.VaultCredentialRef> vaultCredentials,
                                                 Map<String, String> envVars) {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), vaultCredentials, envVars, List.of()))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(), null))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sess_toolkit_it", OWNER_ID))
                .withMcpConnections(connections)
                .build();
    }

    /** 一次装配期 discovery 的产物：工具台账 + 逐服务器注册终态。 */
    private record Discovery(Toolkit toolkit, List<McpServerRegistrationResult> results) {

        McpServerRegistrationResult onlyResult() {
            assertEquals(1, results.size());
            return results.get(0);
        }
    }

    /** 装配期 discovery：平台配置产出 → 框架注册器（{@code HarnessAgent.build()} 内同一调用）。 */
    private Discovery discover(AgentAssemblySpec spec) {
        ToolsConfig config = agentFactory.buildToolsConfig(spec);
        Toolkit toolkit = new Toolkit();
        List<McpServerRegistrationResult> results = new ArrayList<>();
        McpServerRegistrar.register(toolkit, config.getMcpServers(), results::add);
        return new Discovery(toolkit, results);
    }

    /** 某 MCP 服务器在工具台账中的已注册工具名（经 {@code ToolBase.getMcpName()} 归属，与实名口径解耦）。 */
    private static List<String> toolNamesOfServer(Toolkit toolkit, String serverName) {
        List<String> names = new ArrayList<>();
        for (String name : toolkit.getToolNames()) {
            if (toolkit.getTool(name) instanceof ToolBase tool && serverName.equals(tool.getMcpName())) {
                names.add(name);
            }
        }
        return names;
    }

    /** 取某服务器的注册终态。 */
    private static McpServerRegistrationResult resultOf(Discovery discovery, String serverName) {
        return discovery.results().stream()
                .filter(result -> serverName.equals(result.serverName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到服务器注册终态: " + serverName));
    }

    /** 必然拒绝连接的本地地址：借系统分配端口后立即释放，连接即 {@code refused}。 */
    private static String unreachableLocalUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/mcp";
        }
    }

    @AfterEach
    void cleanUpCredentialEgressRows() {
        jdbcTemplate.update("DELETE FROM chat_event WHERE session_id = ?", LEDGER_SESSION_ID);
        for (String vaultId : createdVaultIds) {
            jdbcTemplate.update("DELETE FROM vault_credentials WHERE vault_id = ?", vaultId);
            jdbcTemplate.update("DELETE FROM vaults WHERE vault_id = ?", vaultId);
        }
        createdVaultIds.clear();
    }
}