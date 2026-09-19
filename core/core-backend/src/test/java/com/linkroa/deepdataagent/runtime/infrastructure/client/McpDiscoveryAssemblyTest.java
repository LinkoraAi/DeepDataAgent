package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.service.McpCredentialResolver;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.shared.config.EgressProperties;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 装配期 discovery 端到端单测（align-qoder-vault-credential-capabilities D12 / D16）。
 * <p>驱动链路为<b>平台真实产出的配置</b>：{@link AgentscopeHarnessAgentFactory#buildToolsConfig}
 * 产出的 {@code ToolsConfig.mcpServers}（含按 URL 命中注入的鉴权头与显式超时）→ 框架
 * {@link McpServerRegistrar#register} ——即 HarnessAgent{@code .build()} 内部的同一次调用，
 * 故断言的是装配期真实发生的握手（{@code initialize}）与工具枚举（{@code tools/list}）。</p>
 * <p>覆盖：枚举结果即工具进入模型清单的唯一来源；无响应服务器在显式初始化超时内收敛为失败
 * 而非无限阻塞；握手与枚举携带注入的鉴权头；无凭证命中时不被静默当作空工具集混入；
 * 枚举失败 / 空集按降级处理；未声明 {@code mcp_servers[]} 时零 discovery 请求；
 * 只读注解与版本策略的优先级实测（task 1.18a）；以及装配末端按运行时实名改名后
 * 「实名命中权限规则 + 仍以服务端原始工具名发起调用」（tasks 1.18）。
 * <p>另含需鉴权服务器 + 命中凭证下「只读工具调用成功、凭据只走请求头」（tasks 2.1 离线断言：
 * 真实容器 / 网络部分另以集成用例承载）。</p>
 * <p>工具实名口径：{@code discover()} 只做 discovery（不含改名），涉及实名的断言显式调用
 * {@link AgentscopeHarnessAgentFactory#renameMcpTools}——即 {@code build()} 内同一次调用。</p>
 */
class McpDiscoveryAssemblyTest {

    /** 测试用超时：远小于框架默认值（120s / 30s），使「超时未下发」时用例快速失败而非长时间挂起。 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(2);

    /** 断言超时收敛的上界：收敛应发生在配置的 {@link #TEST_TIMEOUT} 量级，远超上界即说明超时未生效。 */
    private static final Duration CONVERGENCE_BOUND = Duration.ofSeconds(10);

    private final AgentscopeHarnessAgentFactory factory = newFactory();

    private AgentscopeHarnessAgentFactory newFactory() {
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory();
        ReflectionTestUtils.setField(factory, "mcpCredentialResolver", new McpCredentialResolver());
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMcpRequestTimeout(TEST_TIMEOUT);
        properties.setMcpInitializationTimeout(TEST_TIMEOUT);
        ReflectionTestUtils.setField(factory, "runtimeProperties", properties);
        // 假 MCP 服务器绑定回环地址：以显式白名单开关放行（内网自建场景，design D8）——
        // 边界拦截本身由 AgentscopeHarnessAgentFactoryTest 的预检用例覆盖
        EgressProperties egressProperties = new EgressProperties();
        egressProperties.setAllowPrivateNetwork(true);
        ReflectionTestUtils.setField(factory, "egressProperties", egressProperties);
        return factory;
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

    /** 携带 MCP 连接声明与保管库凭证的装配规格（与 {@code AgentscopeHarnessAgentFactoryTest} 基线一致）。 */
    private static AgentAssemblySpec specWithMcp(List<AgentAssemblySpec.McpConnection> connections,
                                                 List<AgentAssemblySpec.VaultCredentialRef> vaultCredentials) {
        return specWithMcp(connections, vaultCredentials, null);
    }

    /** 附工具可见性约束的装配规格（内置白名单用例）。 */
    private static AgentAssemblySpec specWithMcp(List<AgentAssemblySpec.McpConnection> connections,
                                                 List<AgentAssemblySpec.VaultCredentialRef> vaultCredentials,
                                                 AgentAssemblySpec.ToolVisibility toolVisibility) {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), vaultCredentials, Map.of(), List.of()))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(), toolVisibility))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sess_toolkit_1", 7L))
                .withMcpConnections(connections)
                .build();
    }

    /** 一次装配期 discovery 的产物：工具台账 + 逐服务器注册终态。 */
    private record Discovery(Toolkit toolkit, List<McpServerRegistrationResult> results) {

        /** 单连接场景下的注册终态。 */
        McpServerRegistrationResult onlyResult() {
            assertEquals(1, results.size());
            return results.get(0);
        }
    }

    /** 装配期 discovery：平台配置产出 → 框架注册器（HarnessAgent 内同一调用）。 */
    private Discovery discover(AgentAssemblySpec spec) {
        ToolsConfig config = factory.buildToolsConfig(spec);
        Toolkit toolkit = new Toolkit();
        List<McpServerRegistrationResult> results = new ArrayList<>();
        McpServerRegistrar.register(toolkit, config.getMcpServers(), results::add);
        return new Discovery(toolkit, results);
    }

    /** 某 MCP 服务器在工具台账中的已注册工具名（经 {@code ToolBase.getMcpName()} 归属，与实名口径解耦）。 */
    private static Set<String> toolNamesOfServer(Toolkit toolkit, String serverName) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : toolkit.getToolNames()) {
            if (toolkit.getTool(name) instanceof ToolBase tool
                    && serverName.equals(tool.getMcpName())) {
                names.add(name);
            }
        }
        return names;
    }

    @Test
    void should_completeHandshakeAndEnumerateTools_when_discovery_given_reachableServer() throws IOException {
        // given（可达且鉴权有效的服务器，暴露两个工具）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())), List.of());

            // when（装配期 discovery）
            Discovery discovery = discover(spec);

            // then（握手与工具枚举真实发生；枚举出的工具即进入模型工具清单的工具集合）
            assertEquals(McpServerRegistrationResult.Status.SUCCESS, discovery.onlyResult().status());
            assertFalse(server.requestsOf("initialize").isEmpty());
            assertFalse(server.requestsOf("tools/list").isEmpty());
            assertEquals(2, toolNamesOfServer(discovery.toolkit(), "weather").size());
        }
    }

    @Test
    void should_convergeWithinConfiguredTimeout_when_discovery_given_serverAcceptingConnectionButNeverHandshaking()
            throws IOException {
        // given（接受 TCP 连接但永不回握手响应——未下发超时时会把装配从「失败降级」拖成「长期等待」）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            server.setCompletesHandshake(false);
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("silent", server.url())), List.of());

            // when（以远大于配置超时的上界兜底：超时未生效则此处直接失败而非无限挂起）
            Discovery discovery = assertTimeoutPreemptively(CONVERGENCE_BOUND, () -> discover(spec));

            // then（在显式初始化超时内收敛为失败降级，工具不入清单）
            assertEquals(McpServerRegistrationResult.Status.FAILED, discovery.onlyResult().status());
            assertFalse(server.requestsOf("initialize").isEmpty());
            assertTrue(toolNamesOfServer(discovery.toolkit(), "silent").isEmpty());
        }
    }

    @Test
    void should_carryInjectedBearerHeaderOnEveryRequest_when_discovery_given_staticBearerCredential()
            throws IOException {
        // given（static_bearer 凭证的 mcp_server_url 与服务器 URL 全等）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())),
                    List.of(new AgentAssemblySpec.VaultCredentialRef(
                            "vault_1", "cr_1", "static_bearer", server.url(), "ghp_discovery-token")));

            // when
            Discovery discovery = discover(spec);

            // then（握手与枚举的每一次请求都带注入的鉴权头——无鉴权 discovery 会被需鉴权服务器
            //      拒绝或回空集，表现为「工具静默缺失」而无从归因）
            assertEquals(McpServerRegistrationResult.Status.SUCCESS, discovery.onlyResult().status());
            assertFalse(server.requests().isEmpty());
            for (FakeMcpServer.RecordedRequest request : server.requests()) {
                assertEquals("Bearer ghp_discovery-token", request.header("Authorization"),
                        "未携带鉴权头的 discovery 请求: " + request.method());
            }
        }
    }

    @Test
    void should_carryBearerHeaderOnEveryRequest_when_discovery_given_mcpOauthCredential() throws IOException {
        // given（mcp_oauth 凭证：discovery 同用其令牌头）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())),
                    List.of(new AgentAssemblySpec.VaultCredentialRef(
                            "vault_1", "cr_1", "mcp_oauth", server.url(), "oauth-discovery-token")));

            // when
            Discovery discovery = discover(spec);

            // then
            assertEquals(McpServerRegistrationResult.Status.SUCCESS, discovery.onlyResult().status());
            assertFalse(server.requests().isEmpty());
            for (FakeMcpServer.RecordedRequest request : server.requests()) {
                assertEquals("Bearer oauth-discovery-token", request.header("Authorization"),
                        "未携带鉴权头的 discovery 请求: " + request.method());
            }
        }
    }

    @Test
    void should_degradeConnection_when_discovery_given_serverRequiringAuthAndNoCredentialHit()
            throws IOException {
        // given（需鉴权服务器 + 本轮无凭证命中：连接以无鉴权方式发出）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            server.setRequiresBearer(true);
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())), List.of());

            // when
            Discovery discovery = discover(spec);

            // then（按 D12 降级：不阻断装配、该连接工具不入清单——MUST NOT 以空工具集静默混入）
            assertEquals(McpServerRegistrationResult.Status.FAILED, discovery.onlyResult().status());
            assertTrue(toolNamesOfServer(discovery.toolkit(), "weather").isEmpty());
        }
    }

    @Test
    void should_skipConnectionToolsAndKeepAssemblyRunning_when_discovery_given_enumerationFails()
            throws IOException {
        // given（握手成功但 tools/list 回 JSON-RPC 错误）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            server.setFailsToolList(true);
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())), List.of());

            // when（不抛异常、不阻断装配）
            Discovery discovery = discover(spec);

            // then（该连接工具不入模型工具清单）
            assertEquals(McpServerRegistrationResult.Status.FAILED, discovery.onlyResult().status());
            assertFalse(server.requestsOf("tools/list").isEmpty());
            assertTrue(toolNamesOfServer(discovery.toolkit(), "weather").isEmpty());
        }
    }

    @Test
    void should_skipConnectionToolsAndKeepAssemblyRunning_when_discovery_given_emptyEnumeration()
            throws IOException {
        // given（握手成功但枚举为空集）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            server.setEmptyToolList(true);
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())), List.of());

            // when（不抛异常、不阻断装配）
            Discovery discovery = discover(spec);

            // then（空清单同样只是「没有工具」——不阻断轮次，也不产生任何规则匹配面）
            assertEquals(McpServerRegistrationResult.Status.SUCCESS, discovery.onlyResult().status());
            assertTrue(toolNamesOfServer(discovery.toolkit(), "weather").isEmpty());
        }
    }

    @Test
    void should_issueNoDiscoveryRequest_when_discovery_given_noDeclaredMcpServers() throws IOException {
        // given（版本未声明 mcp_servers[]；假服务器在旁待命用作零命中的证物）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec spec = specWithMcp(List.of(), List.of());

            // when
            ToolsConfig config = factory.buildToolsConfig(spec);
            Toolkit toolkit = new Toolkit();
            List<McpServerRegistrationResult> results = new ArrayList<>();
            McpServerRegistrar.register(toolkit, config.getMcpServers(), results::add);

            // then（不下发任何 MCP 配置、不产生注册调用、假服务器零命中）
            assertNull(config.getMcpServers());
            assertTrue(results.isEmpty());
            assertTrue(toolkit.getToolNames().isEmpty());
            assertTrue(server.requests().isEmpty());
        }
    }

    @Test
    void should_askBeforeReadOnlyAllowance_when_permissionEngine_given_alwaysAskRuleForReadOnlyMcpTool()
            throws IOException {
        // given（服务端声明 readOnlyHint: true 的只读工具——框架对其有「只读自动放行」的自检）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())), List.of());
            Discovery discovery = discover(spec);
            ToolBase readOnlyTool = readOnlyToolOfServer(discovery.toolkit(), "weather");

            // when①（无任何规则、DEFAULT 模式：仅走工具自检）
            PermissionDecision selfCheckOnly = engine(PermissionContextState.builder()
                    .mode(PermissionMode.DEFAULT).build()).checkPermission(readOnlyTool, Map.of()).block();

            // when②（版本策略 always_ask 按该工具实名落 ASK 规则）
            PermissionContextState context = PermissionContextState.builder()
                    .mode(PermissionMode.BYPASS)
                    .addAskRule(readOnlyTool.getName(), new PermissionRule(
                            readOnlyTool.getName(), null, PermissionBehavior.ASK, "agentToolPolicy"))
                    .build();
            PermissionDecision underPolicy = engine(context).checkPermission(readOnlyTool, Map.of()).block();

            // then①（「只读自动放行」确实存在：无策略时不被拦）
            assertNotNull(selfCheckOnly);
            assertEquals(PermissionBehavior.ALLOW, selfCheckOnly.getBehavior());

            // then②（求值序中 ASK 规则先于工具自检命中：版本策略覆盖只读自动放行，
            //        故「MCP 工具的 always_ask 对只读工具同样生效」的承诺无需收窄）
            assertNotNull(underPolicy);
            assertEquals(PermissionBehavior.ASK, underPolicy.getBehavior());
        }
    }

    @Test
    void should_registerUnderRuntimeNameAndKeepServerOwnership_when_renameMcpTools_given_discoveredMcpTools()
            throws IOException {
        // given（可达服务器暴露两个工具：框架按服务端原始工具名注册，实名约定尚未成立）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())), List.of());
            Discovery discovery = discover(spec);
            assertTrue(discovery.toolkit().getToolNames().containsAll(List.of("get_weather", "delete_city")));

            // when（装配末端改名——即 build() 内 renameMcpTools 的同一次调用）
            factory.renameMcpTools(discovery.toolkit());

            // then（台账键为运行时实名；服务器归属与只读标记随包装体保留，非 MCP 工具不受影响）
            Set<String> names = discovery.toolkit().getToolNames();
            assertEquals(Set.of("mcp__weather__get_weather", "mcp__weather__delete_city"), names);
            ToolBase renamed = (ToolBase) discovery.toolkit().getTool("mcp__weather__get_weather");
            assertEquals("weather", renamed.getMcpName());
            assertTrue(renamed.isReadOnly());
            assertTrue(discovery.toolkit().getTool("mcp__weather__delete_city") instanceof ToolBase writer
                    && !writer.isReadOnly());
        }
    }

    @Test
    void should_keepMcpTools_when_applyBuiltinWhitelist_given_allowlistConstraints() throws IOException {
        // given（可达服务器枚举出两个工具，版本另带一份只治理内置工具的白名单）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec spec = specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())), List.of(),
                    new AgentAssemblySpec.ToolVisibility(List.of("Bash", "Read"), null));
            Discovery discovery = discover(spec);
            factory.renameMcpTools(discovery.toolkit());

            // when（装配末端施加内置白名单——即 build() 内同一次调用）
            factory.applyBuiltinWhitelist(discovery.toolkit(), spec);

            // then（白名单只治理内置工具：MCP 实名工具不在名单内亦不被裁剪——框架 allow 语义会
            //        把它们整体剔除，届时版本的 mcp_toolset 声明、策略规则与事件投影一并失效）
            assertTrue(discovery.toolkit().getToolNames().containsAll(
                    List.of("mcp__weather__get_weather", "mcp__weather__delete_city")));
        }
    }

    @Test
    void should_keepNamesUnchanged_when_renameMcpTools_given_alreadyRenamedTools() throws IOException {
        // given（已完成一次改名的台账；框架未来若自行实现前缀命名，本方法据前缀跳过）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            Toolkit toolkit = discoveredToolkit(server);
            factory.renameMcpTools(toolkit);
            Set<String> afterFirstPass = Set.copyOf(toolkit.getToolNames());

            // when（重复执行改名）
            factory.renameMcpTools(toolkit);

            // then（幂等：不产生双重前缀、不丢工具）
            assertEquals(afterFirstPass, toolkit.getToolNames());
        }
    }

    @Test
    void should_callServerToolByOriginalName_when_callAsync_given_mcpToolRegisteredUnderRuntimeName()
            throws IOException {
        // given（改名后以运行时实名在册的工具）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            Toolkit toolkit = discoveredToolkit(server);
            factory.renameMcpTools(toolkit);
            ToolBase renamed = (ToolBase) toolkit.getTool("mcp__weather__get_weather");

            // when（按实名调用）
            ToolResultBlock result = renamed.callAsync(ToolCallParam.builder()
                    .input(Map.of("city", "杭州")).build()).block();

            // then（调用原样委派原工具：服务器收到的是服务端原始工具名——改名只改平台侧身份，
            //       MUST NOT 把平台实名为名的请求发给第三方服务器）
            assertNotNull(result);
            assertEquals("get_weather", server.lastCalledToolName());
        }
    }

    @Test
    void should_callReadOnlyToolSuccessfully_when_callAsync_given_bearerRequiringServerAndMatchingCredential()
            throws IOException {
        // given（需鉴权服务器 + URL 全等命中的 static_bearer 凭证：唯一可行的连接方式就是注入的鉴权头）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            server.setRequiresBearer(true);
            Discovery discovery = discover(specWithMcp(
                    List.of(new AgentAssemblySpec.McpConnection("weather", server.url())),
                    List.of(new AgentAssemblySpec.VaultCredentialRef(
                            "vault_1", "cr_1", "static_bearer", server.url(), "ghp_readonly-call-token"))));
            factory.renameMcpTools(discovery.toolkit());
            ToolBase readOnly = (ToolBase) discovery.toolkit().getTool("mcp__weather__get_weather");

            // when（模型按运行时实名调用只读工具）
            ToolResultBlock result = readOnly.callAsync(ToolCallParam.builder()
                    .input(Map.of("city", "杭州")).build()).block();

            // then（连接未因鉴权失败降级、调用成功返回；服务器按服务端原始工具名收到请求，
            //       且凭据只出现在请求头、MUST NOT 出现在请求体）
            assertNotNull(result);
            assertEquals(McpServerRegistrationResult.Status.SUCCESS, discovery.onlyResult().status());
            List<FakeMcpServer.RecordedRequest> calls = server.requestsOf("tools/call");
            assertEquals(1, calls.size());
            assertEquals("get_weather", server.lastCalledToolName());
            assertEquals("Bearer ghp_readonly-call-token", calls.get(0).header("Authorization"));
            assertFalse(calls.get(0).body().contains("ghp_readonly-call-token"));
        }
    }

    @Test
    void should_hitAskRule_when_permissionEngine_given_versionPolicyTranslatedToRuntimeName()
            throws IOException {
        // given（版本为服务端原始工具名 get_weather 配置 always_ask）
        try (FakeMcpServer server = new FakeMcpServer(weatherTools())) {
            Toolkit toolkit = discoveredToolkit(server);
            factory.renameMcpTools(toolkit);
            ToolBase renamed = (ToolBase) toolkit.getTool("mcp__weather__get_weather");

            // when①（规则由工厂按版本策略产出——MCP 策略项携带服务器名，翻译为运行时实名）
            PermissionContextState context = factory.buildPermissionContext(List.of(
                    new AgentAssemblySpec.ToolExecutionPolicy("get_weather",
                            AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK, "weather")));
            PermissionDecision underVersionPolicy = engine(context).checkPermission(renamed, Map.of()).block();

            // when②（对照：规则键为未翻译的服务端原始工具名）
            PermissionDecision underRawNameRule = engine(PermissionContextState.builder()
                    .mode(PermissionMode.BYPASS)
                    .addAskRule("get_weather", new PermissionRule(
                            "get_weather", null, PermissionBehavior.ASK, "agentToolPolicy"))
                    .build()).checkPermission(renamed, Map.of()).block();

            // then①（实名命中：版本的 always_ask 真实生效）
            assertNotNull(context);
            assertEquals(Set.of("mcp__weather__get_weather"), context.getAskRules().keySet());
            assertNotNull(underVersionPolicy);
            assertEquals(PermissionBehavior.ASK, underVersionPolicy.getBehavior());

            // then②（按原始工具名落表永不命中：只读工具退回自检自动放行——即「静默失效」的实证）
            assertNotNull(underRawNameRule);
            assertEquals(PermissionBehavior.ALLOW, underRawNameRule.getBehavior());
        }
    }

    /** 单连接场景下仅取工具台账（用于实名相关断言，不关心注册终态）。 */
    private Toolkit discoveredToolkit(FakeMcpServer server) {
        return discover(specWithMcp(
                List.of(new AgentAssemblySpec.McpConnection("weather", server.url())), List.of())).toolkit();
    }

    @Test
    void should_keepReachableConnectionTools_when_discovery_given_oneUnreachableServerAmongTwo() throws IOException {
        // given（两个声明连接：weather 可达、dead 为必然拒绝连接的本地端口）
        try (FakeMcpServer reachable = new FakeMcpServer(weatherTools())) {
            AgentAssemblySpec spec = specWithMcp(List.of(
                    new AgentAssemblySpec.McpConnection("weather", reachable.url()),
                    new AgentAssemblySpec.McpConnection("dead", unusedLocalUrl())), List.of());

            // when（装配期 discovery：单连接不可达 MUST NOT 抛出、MUST NOT 阻断装配）
            Discovery discovery = discover(spec);

            // then（失败连接留下含服务器名与失败类别的诊断、其工具不入清单；可达连接的握手 /
            //       枚举与工具集合照常——即「单连接失败不影响其余」）
            assertEquals(2, discovery.results().size());
            McpServerRegistrationResult dead = resultOf(discovery, "dead");
            assertEquals(McpServerRegistrationResult.Status.FAILED, dead.status());
            assertNotNull(dead.cause());
            assertFalse(dead.cause().getClass().getSimpleName().isBlank());
            assertTrue(toolNamesOfServer(discovery.toolkit(), "dead").isEmpty());
            assertEquals(McpServerRegistrationResult.Status.SUCCESS, resultOf(discovery, "weather").status());
            assertEquals(Set.of("get_weather", "delete_city"),
                    toolNamesOfServer(discovery.toolkit(), "weather"));
        }
    }

    /** 必然拒绝连接的本地地址：借系统分配端口后立即释放，连接即 {@code refused}。 */
    private static String unusedLocalUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/mcp";
        }
    }

    /** 取某服务器的注册终态。 */
    private static McpServerRegistrationResult resultOf(Discovery discovery, String serverName) {
        return discovery.results().stream()
                .filter(result -> serverName.equals(result.serverName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到服务器注册终态: " + serverName));
    }

    private static PermissionEngine engine(PermissionContextState context) {
        return new PermissionEngine(context);
    }

    /** 取某服务器注册工具中声明只读的那一个（断言对象为「只读 + 策略」冲突场景）。 */
    private static ToolBase readOnlyToolOfServer(Toolkit toolkit, String serverName) {
        for (String name : toolkit.getToolNames()) {
            if (toolkit.getTool(name) instanceof ToolBase tool && serverName.equals(tool.getMcpName())
                    && tool.isReadOnly()) {
                return tool;
            }
        }
        throw new IllegalStateException("未找到只读 MCP 工具: " + serverName);
    }
}