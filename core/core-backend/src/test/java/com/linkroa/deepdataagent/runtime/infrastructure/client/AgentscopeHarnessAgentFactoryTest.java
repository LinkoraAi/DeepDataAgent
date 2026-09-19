package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.agent.application.dto.AgentMcpServerDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.port.AgentVersionAssemblyPort;
import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliveryPort;
import com.linkroa.deepdataagent.runtime.application.service.assembly.RuntimeAgentAssemblyService;
import com.linkroa.deepdataagent.runtime.application.service.assembly.SessionMountMaterializer;
import com.linkroa.deepdataagent.runtime.domain.factory.SessionWorkspacePort;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.MemoryStoreRef;
import com.linkroa.deepdataagent.runtime.domain.service.McpCredentialResolver;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import com.linkroa.deepdataagent.shared.config.EgressProperties;
import com.linkroa.deepdataagent.vault.application.dto.ResolvedVaultCredentialDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialResolutionPort;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.tool.WebTools;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AgentscopeHarnessAgentFactory} 装配单测（register-sandbox-artifacts D4 /
 * sandbox-workspace-file-mounts D8 / D14）。
 * <p>覆盖：手动工具基座仅 TodoTools（交付工具自 Harness 2.0.3 起由框架 build()
 * 按 artifactDeliveryTarget 自动注册，不在平台 Toolkit 台账；记忆库清单占位工具
 * memory_store_list 已下线、不再装配）、
 * 工具可见性约束的落地（框架侧仅 deny；内置白名单由平台侧
 * {@link AgentscopeHarnessAgentFactory#applyBuiltinWhitelist} 在 MCP 实名化之后施加，
 * 见 enforce-tool-visibility-execution D1/D2）、
 * 权限策略契约名→运行时实名规则构型（enforce-tool-visibility-execution D2）、
 * 系统提示挂载清单段落的有无两态与保序（D14）、bind mount 工作区规格的目录实况三态
 * 与只读条目构型（D8 / D18）、AGENTS.md 恒物化到 per-agent 工作区根（D4）、
 * 沙箱数据面环境变量零凭证明文（align-qoder-vault-credential-capabilities tasks 2.2 离线断言）。</p>
 */
class AgentscopeHarnessAgentFactoryTest {

    private final SessionWorkspacePort sessionWorkspacePort = mock(SessionWorkspacePort.class);
    private final AgentscopeHarnessAgentFactory factory = newFactory(false);

    private AgentscopeHarnessAgentFactory newFactory(boolean allowPrivateNetwork) {
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory();
        ReflectionTestUtils.setField(factory, "artifactDeliveryPort",
                mock(ArtifactDeliveryPort.class));
        ReflectionTestUtils.setField(factory, "sessionWorkspacePort", sessionWorkspacePort);
        // MCP 凭据解析器为无依赖纯逻辑领域服务，装配单测直注真实实现（不 mock）
        ReflectionTestUtils.setField(factory, "mcpCredentialResolver", new McpCredentialResolver());
        // MCP 请求 / 初始化超时取自运行时配置载体（默认 30s / 15s，可经 APP_AGENT_MCP_* 覆盖）
        ReflectionTestUtils.setField(factory, "runtimeProperties", new AgentRuntimeProperties());
        // 出网信任边界开关（默认关闭 = 拒绝内网目标；本类用例的 MCP URL 均为公网域名）
        ReflectionTestUtils.setField(factory, "egressProperties", egressProperties(allowPrivateNetwork));
        return factory;
    }

    /** 出网信任边界配置载体（白名单开关，design D8）。 */
    private static EgressProperties egressProperties(boolean allowPrivateNetwork) {
        EgressProperties properties = new EgressProperties();
        properties.setAllowPrivateNetwork(allowPrivateNetwork);
        return properties;
    }

    /** 以真实框架工具实体装配台账（内置工具裁撤用例的观测对象）。 */
    private static Toolkit toolkitWith(Object... tools) {
        Toolkit toolkit = new Toolkit();
        for (Object tool : tools) {
            toolkit.registerTool(tool);
        }
        return toolkit;
    }

    private static AgentAssemblySpec specWith(List<MemoryStoreRef> memoryStoreRefs) {
        return specWith(memoryStoreRefs, null);
    }

    private static AgentAssemblySpec specWith(List<MemoryStoreRef> memoryStoreRefs,
                                              AgentAssemblySpec.ToolVisibility toolVisibility) {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), memoryStoreRefs, List.of(), Map.of(), List.of()))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(), toolVisibility))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sess_toolkit_1", 7L))
                .build();
    }

    @Test
    void should_registerOnlyTodoBaseline_when_buildToolkit_given_specWithoutMemoryStores() {
        // given（最小规格：无记忆库引用）
        AgentAssemblySpec spec = specWith(List.of());

        // when
        Toolkit toolkit = factory.buildToolkit(spec);

        // then（平台 Toolkit 仅 TodoTools；交付工具由框架 build() 自动注册、记忆工具未装配）
        Set<String> names = toolkit.getToolNames();
        assertTrue(names.contains(HarnessToolNames.TODO_WRITE));
        assertTrue(!names.contains("memory_store_list"));
        assertTrue(!names.contains("deliver_artifact"));
        assertTrue(!names.contains("DeliverArtifacts"));
    }

    @Test
    void should_defaultDenyWebTools_when_buildToolsConfig_given_noVisibilityConstraint() {
        // given（可见性约束为空名单 = 无约束；2.0.3 起框架无条件注册联网工具，平台默认关闭（D9））
        AgentAssemblySpec spec = specWith(List.of(),
                new AgentAssemblySpec.ToolVisibility(null, null));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（不再返回 null：恒下发 deny=[web_fetch, web_search]，升级不静默扩大能力面）
        assertNotNull(config);
        assertNull(config.getAllow());
        assertEquals(List.of("web_fetch", "web_search"), config.getDeny());
    }

    @Test
    void should_defaultDenyWebTools_when_buildToolsConfig_given_nullVisibility() {
        // given（toolVisibility 整体为 null 的最小规格）
        AgentAssemblySpec spec = specWith(List.of());

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then：恒有主张——仍下发联网默认 deny
        assertNotNull(config);
        assertEquals(List.of("web_fetch", "web_search"), config.getDeny());
    }

    @Test
    void should_notSetAllowNorDeny_when_buildToolsConfig_given_allowlistOnly() {
        // given（白名单含可映射名——含 2.0.3 新注册实体的 WebFetch——未配置记忆库）
        AgentAssemblySpec spec = specWith(List.of(),
                new AgentAssemblySpec.ToolVisibility(List.of("Bash", "Read", "WebFetch"), null));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（白名单不经框架 allow 下达——allow 语义会连同 MCP 工具一并剔除，改由平台侧
        //     applyBuiltinWhitelist 施加；白名单场景亦不设 deny：联网两名已被白名单天然排除）
        assertNotNull(config);
        assertNull(config.getAllow());
        assertNull(config.getDeny());
    }

    @Test
    void should_cropUnlistedBuiltinsAndKeepTodoBaseline_when_applyBuiltinWhitelist_given_allowlist() {
        // given（台账含内置 todo_write 与框架无条件注册的联网两名；白名单仅列 Read）
        Toolkit toolkit = toolkitWith(new TodoTools(), new WebTools.WebFetchTool(), new WebTools.WebSearchTool());
        AgentAssemblySpec spec = specWith(List.of(),
                new AgentAssemblySpec.ToolVisibility(List.of("Read"), null));

        // when
        factory.applyBuiltinWhitelist(toolkit, spec);

        // then（名单外内置工具（含无契约映射者）移除；平台辅助工具 todo_write 恒豁免）
        assertEquals(Set.of(HarnessToolNames.TODO_WRITE), toolkit.getToolNames());
    }

    @Test
    void should_keepToolkitUntouched_when_applyBuiltinWhitelist_given_noWhitelist() {
        // given（无白名单约束 = 全量基座暴露）
        Toolkit toolkit = toolkitWith(new TodoTools(), new WebTools.WebFetchTool());
        Set<String> before = Set.copyOf(toolkit.getToolNames());

        // when
        factory.applyBuiltinWhitelist(toolkit, specWith(List.of()));

        // then（不介入：联网两名仍由 ToolsConfig.deny 治理）
        assertEquals(before, toolkit.getToolNames());
    }

    @Test
    void should_mergeDefaultWebDeny_when_buildToolsConfig_given_denylistOnly() {
        // given（仅黑名单约束：allow 不设 = 基座全量放行，deny = 翻译隐藏名单 + 联网默认两名）
        AgentAssemblySpec spec = specWith(List.of(),
                new AgentAssemblySpec.ToolVisibility(null, List.of("Write", "Glob")));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（去重保序：翻译名单在前，联网两名并入其后；Write 翻译出的 write_file 不与默认两名重复）
        assertNotNull(config);
        assertNull(config.getAllow());
        assertEquals(List.of("write_file", "glob_files", "web_fetch", "web_search"), config.getDeny());
    }

    @Test
    void should_keepUserDenyWithoutWebMerge_when_buildToolsConfig_given_allowlistWithHiddenTools() {
        // given（白名单 + 黑名单并存：deny 承载用户显式隐藏名单，但不追加联网两名（白名单已排除））
        AgentAssemblySpec spec = specWith(List.of(),
                new AgentAssemblySpec.ToolVisibility(List.of("Bash", "WebSearch"), List.of("Write")));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then：白名单不下发（含显式列入的 WebSearch，平台侧按实名放行）；deny 仅用户隐藏名单、无联网默认名
        assertNull(config.getAllow());
        assertEquals(List.of("write_file"), config.getDeny());
    }

    @Test
    void should_defaultDenyWebTools_when_buildToolsConfig_given_hiddenToolsAllUnmapped() {
        // given（隐藏名单在 Harness 2.0.3 仍全部无注册实体——ImageGen / ImageSearch，翻译后为空）
        AgentAssemblySpec spec = specWith(List.of(),
                new AgentAssemblySpec.ToolVisibility(null, List.of("ImageGen", "ImageSearch")));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（翻译名单为空仍下发联网默认 deny——恒有主张，不再有 null 语义）
        assertNotNull(config);
        assertEquals(List.of("web_fetch", "web_search"), config.getDeny());
    }

    // ==================== MCP 接线（ToolsConfig.mcpServers，JVM 侧鉴权头注入） ====================

    /** 携带 MCP 连接声明与保管库凭证的装配规格（其余参数与 specWith 基线一致）。 */
    private static AgentAssemblySpec specWithMcp(List<AgentAssemblySpec.McpConnection> connections,
                                                 List<AgentAssemblySpec.VaultCredentialRef> vaultCredentials) {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), vaultCredentials, Map.of(), List.of()))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(), null))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sess_toolkit_1", 7L))
                .withMcpConnections(connections)
                .build();
    }

    @Test
    void should_notSetMcpServers_when_buildToolsConfig_given_noConnections() {
        // given（版本未声明 MCP：mcpServers 不下发，行为零扰动）
        AgentAssemblySpec spec = specWith(List.of());

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then
        assertNull(config.getMcpServers());
    }

    @Test
    void should_setStreamableHttpServerWithBearerHeader_when_buildToolsConfig_given_connectionWithVaultCredential() {
        // given（一路连接 + 按 URL 全等命中的 static_bearer 凭证；服务器名称不参与匹配）
        AgentAssemblySpec spec = specWithMcp(
                List.of(new AgentAssemblySpec.McpConnection("github", "https://api.githubcopilot.com/mcp")),
                List.of(new AgentAssemblySpec.VaultCredentialRef(
                        "vault_1", "cr_1", "static_bearer", "https://api.githubcopilot.com/mcp",
                        "ghp_plain-token")));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（Map 键=连接名，transport=streamable-http，Authorization 头承载 Bearer 明文——
        //     仅进 JVM 侧 MCP 客户端，不进沙箱环境）
        assertNotNull(config.getMcpServers());
        assertEquals(Set.of("github"), config.getMcpServers().keySet());
        McpServerConfig server = config.getMcpServers().get("github");
        assertEquals("streamable-http", server.getTransport());
        assertEquals("https://api.githubcopilot.com/mcp", server.getUrl());
        assertEquals("Bearer ghp_plain-token", server.getHeaders().get("Authorization"));
    }

    @Test
    void should_setBearerHeaderForOauthCredential_when_buildToolsConfig_given_mcpOauthCredential() {
        // given（mcp_oauth 型凭证：与 static_bearer 同口径产出 Authorization 头——
        //        discovery 握手与工具枚举同用该头，故两类型都必须可注入）
        AgentAssemblySpec spec = specWithMcp(
                List.of(new AgentAssemblySpec.McpConnection("github", "https://api.githubcopilot.com/mcp")),
                List.of(new AgentAssemblySpec.VaultCredentialRef(
                        "vault_1", "cr_1", "mcp_oauth", "https://api.githubcopilot.com/mcp",
                        "oauth-access-token")));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then
        McpServerConfig server = config.getMcpServers().get("github");
        assertEquals("Bearer oauth-access-token", server.getHeaders().get("Authorization"));
    }

    @Test
    void should_setExplicitTimeouts_when_buildToolsConfig_given_declaredConnections() {
        // given（D12：装配期同步阻塞完成握手 + 工具枚举，超时必须显式下发，
        //        否则「无响应服务器」会从「失败降级」变成「长期等待」；取值来自运行时配置载体）
        AgentAssemblySpec spec = specWithMcp(
                List.of(new AgentAssemblySpec.McpConnection("github", "https://api.githubcopilot.com/mcp")),
                List.of());

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then
        McpServerConfig server = config.getMcpServers().get("github");
        assertNotNull(server.getTimeout());
        assertNotNull(server.getInitializationTimeout());
        assertEquals(Duration.ofSeconds(30), server.getTimeout());
        assertEquals(Duration.ofSeconds(15), server.getInitializationTimeout());
    }

    @Test
    void should_setServerWithoutHeaders_when_buildToolsConfig_given_connectionWithoutCredential() {
        // given（声明了连接但本轮无保管库命中：连接照常注册、不带鉴权头）
        AgentAssemblySpec spec = specWithMcp(
                List.of(new AgentAssemblySpec.McpConnection("public", "https://public.example.com/mcp")),
                List.of());

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then
        McpServerConfig server = config.getMcpServers().get("public");
        assertNotNull(server);
        assertEquals("streamable-http", server.getTransport());
        assertNull(server.getHeaders());
    }

    // ==================== MCP 建连的出网信任边界预检（design D8） ====================

    @Test
    void should_skipBlockedConnectionAndKeepOthers_when_buildToolsConfig_given_internalTargetWithWhitelistOff() {
        // given（白名单关闭：声明了内网 MCP 与公网 MCP 各一路——越界连接不下发给框架，
        //        其余连接与工具清单照常，即 D12 的「单连接不可用不阻断装配」）
        AgentAssemblySpec spec = specWithMcp(List.of(
                new AgentAssemblySpec.McpConnection("internal", "http://127.0.0.1:9000/mcp"),
                new AgentAssemblySpec.McpConnection("github", "https://api.githubcopilot.com/mcp")),
                List.of());

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（越界连接被剔除，公网连接保留）
        assertEquals(Set.of("github"), config.getMcpServers().keySet());
    }

    @Test
    void should_skipMetadataTarget_when_buildToolsConfig_given_linkLocalAddressAndWhitelistOff() {
        // given（云元数据地址属链路本地：SSRF 高危目标，白名单关闭时必须拦截）
        AgentAssemblySpec spec = specWithMcp(
                List.of(new AgentAssemblySpec.McpConnection("metadata",
                        "http://169.254.169.254/latest/meta-data/mcp")),
                List.of());

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then
        assertNull(config.getMcpServers());
    }

    @Test
    void should_keepPrivateTargetConnection_when_buildToolsConfig_given_whitelistEnabled() {
        // given（内网自建 MCP 场景：显式白名单开启时放行 RFC1918 目标）
        AgentscopeHarnessAgentFactory whitelisted = newFactory(true);
        AgentAssemblySpec spec = specWithMcp(
                List.of(new AgentAssemblySpec.McpConnection("self-hosted", "http://10.0.0.5:9000/mcp")),
                List.of());

        // when
        ToolsConfig config = whitelisted.buildToolsConfig(spec);

        // then
        McpServerConfig server = config.getMcpServers().get("self-hosted");
        assertNotNull(server);
        assertEquals("http://10.0.0.5:9000/mcp", server.getUrl());
    }

    @Test
    void should_diagnoseNameAndHostOnly_when_mcpEgressDiagnostic_given_blockedTargetWithQueryParams() {
        // given（诊断红线：只含服务器名 + 目标主机 + 失败类别，不含完整 URL——查询参数可能携带敏感值）

        // when
        String diagnostic = factory.mcpEgressDiagnostic("internal", "127.0.0.1");

        // then
        assertTrue(diagnostic.contains("internal"));
        assertTrue(diagnostic.contains("127.0.0.1"));
        assertTrue(diagnostic.contains("out_of_trust_boundary"));
        assertFalse(diagnostic.contains("9000"));
        assertFalse(diagnostic.contains("/mcp"));
    }

    @Test
    void should_keepOtherToolsConfig_when_buildToolsConfig_given_connectionsAndWhitelist() {
        // given（白名单 + MCP 连接并存：allow/deny 主张与 mcpServers 互不干扰）
        AgentAssemblySpec spec = AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), List.of(), Map.of(), List.of()))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(),
                        new AgentAssemblySpec.ToolVisibility(List.of("Bash"), null)))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sess_toolkit_1", 7L))
                .withMcpConnections(List.of(
                        new AgentAssemblySpec.McpConnection("svc", "https://mcp.example.com/mcp")))
                .build();

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（白名单不下发（平台侧施加）、无 deny；mcpServers 正常下发——两者互不影响）
        assertNull(config.getAllow());
        assertNull(config.getDeny());
        assertEquals(Set.of("svc"), config.getMcpServers().keySet());
    }

    @Test
    void should_dispatchAssembledMcpServerWithBearerHeader_when_buildToolsConfig_given_assembledSpec() {
        // given（版本声明 MCP 服务器 + 会话挂载 URL 全等命中的 static_bearer 凭证；
        //        规格经真实装配链产出——不直接构造 spec 绕过装配）
        AgentVersionAssemblyPort assemblyPort = mock(AgentVersionAssemblyPort.class);
        VaultCredentialResolutionPort vaultPort = mock(VaultCredentialResolutionPort.class);
        when(assemblyPort.resolve("agent-a", "1", 7L)).thenReturn(new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "台账系统提示词", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", List.of(), null, List.of(), List.of(),
                null, null, List.of(), null,
                List.of(new AgentMcpServerDTO("github", "url", "https://api.githubcopilot.com/mcp"))));
        when(vaultPort.resolveVaultCredentials(7L, List.of("vault_1"))).thenReturn(List.of(
                new ResolvedVaultCredentialDTO("vault_1", "cr_1", "static_bearer",
                        "https://api.githubcopilot.com/mcp", "ghp_plain-token")));
        AgentSession session = AgentSession.createWithMounts("7", "agent-a", "1", "{}", null,
                null, null, List.of(), null, List.of("vault_1"), null);

        // when（装配 → 工厂下发）
        AgentAssemblySpec spec = newAssemblyService(assemblyPort, vaultPort).assemble(session);
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（transport / url / Bearer 头逐项来自装配链；凭证明文仅进 JVM 侧请求头，不进沙箱环境变量）
        McpServerConfig server = config.getMcpServers().get("github");
        assertNotNull(server);
        assertEquals("streamable-http", server.getTransport());
        assertEquals("https://api.githubcopilot.com/mcp", server.getUrl());
        assertEquals("Bearer ghp_plain-token", server.getHeaders().get("Authorization"));
        assertTrue(spec.environmentVariables().isEmpty());
    }

    @Test
    void should_carryOnlySessionEnvVars_when_assemble_given_legalEnvVarsAndVaultCredentialHit() {
        // given（会话挂载合法环境变量 + 命中 static_bearer 凭证：沙箱环境变量唯一来源是
        //        spec.environmentVariables()（工厂 sandboxEnvironment 只取该映射），凭据只进 JVM 侧请求头）
        AgentVersionAssemblyPort assemblyPort = mock(AgentVersionAssemblyPort.class);
        VaultCredentialResolutionPort vaultPort = mock(VaultCredentialResolutionPort.class);
        when(assemblyPort.resolve("agent-a", "1", 7L)).thenReturn(new ResolvedAgentAssemblyDTO(
                "agent-a", 1, "v1", "台账系统提示词", null, "openai:gpt-4", 10, null,
                "https://api.example.com/v1", List.of(), null, List.of(), List.of(),
                null, null, List.of(), null,
                List.of(new AgentMcpServerDTO("github", "url", "https://api.githubcopilot.com/mcp"))));
        when(vaultPort.resolveVaultCredentials(7L, List.of("vault_1"))).thenReturn(List.of(
                new ResolvedVaultCredentialDTO("vault_1", "cr_1", "static_bearer",
                        "https://api.githubcopilot.com/mcp", "ghp_plain-token")));
        AgentSession session = AgentSession.createWithMounts("7", "agent-a", "1", "{}", null,
                null, null, List.of(), null, List.of("vault_1"), "{\"LOG_LEVEL\":\"debug\"}");

        // when（真实装配链：环境变量 JSON 解析 + 凭证解析一次取齐）
        AgentAssemblySpec spec = newAssemblyService(assemblyPort, vaultPort).assemble(session);

        // then（合法会话环境变量原样进入沙箱环境来源；凭证明文不在其中——零凭据红线）
        assertEquals(Map.of("LOG_LEVEL", "debug"), spec.environmentVariables());
        assertFalse(spec.environmentVariables().containsValue("ghp_plain-token"));
    }

    /** 真实装配链（{@link RuntimeAgentAssemblyService}）测试夹具：端口以 mock 注入，其余为无逻辑真实对象。 */
    private static RuntimeAgentAssemblyService newAssemblyService(
            AgentVersionAssemblyPort assemblyPort, VaultCredentialResolutionPort vaultPort) {
        RuntimeAgentAssemblyService service = new RuntimeAgentAssemblyService();
        ReflectionTestUtils.setField(service, "agentVersionAssemblyPort", assemblyPort);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setSandboxImage("python:3.12");
        properties.setSandboxMemoryBytes(8192L);
        properties.setSandboxCpuCount(4L);
        ReflectionTestUtils.setField(service, "runtimeProperties", properties);
        ReflectionTestUtils.setField(service, "vaultCredentialResolutionPort", vaultPort);
        ReflectionTestUtils.setField(service, "sessionMountMaterializer",
                mock(SessionMountMaterializer.class));
        // 会话环境变量 JSON → 键值映射的真实解析器（会话携带环境变量时装配链必经）
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
    }

    // ==================== MCP 注册降级诊断（D12） ====================

    @Test
    void should_notThrow_when_mcpRegistrationListener_given_successFailedAndSkippedResults() {
        // given（框架 McpServerRegistrar 逐条独立 try/catch：单连接失败只跳该条、其余照常注册，
        //        回调自身抛错会被框架吞掉——但平台回调 MUST NOT 依赖该兜底，否则诊断即告丢失）
        McpServerRegistrationListener listener = factory.mcpRegistrationListener();

        // when & then（三种终态回调均不抛：失败连接工具不入清单由框架关闭客户端保障，平台侧不阻断装配）
        listener.onCompleted(McpServerRegistrationResult.success("ok", "streamable-http"));
        listener.onCompleted(McpServerRegistrationResult.failed(
                "github", "streamable-http", new IllegalStateException("connection refused")));
        listener.onCompleted(McpServerRegistrationResult.skipped(
                "weather", "streamable-http", new IllegalArgumentException("blank name")));
    }

    @Test
    void should_includeServerNameTransportAndCauseType_when_mcpRegistrationDiagnostic_given_failedResult() {
        // given（单连接失败终态：cause 为连接异常）
        McpServerRegistrationResult failed = McpServerRegistrationResult.failed(
                "github", "streamable-http", new IllegalStateException("connection refused"));

        // when
        String diagnostic = factory.mcpRegistrationDiagnostic(failed);

        // then（可读诊断三要素齐备：服务器名 / 传输形态 / 失败类别，便于分辨配置未生效与服务器故障）
        assertTrue(diagnostic.contains("github"));
        assertTrue(diagnostic.contains("streamable-http"));
        assertTrue(diagnostic.contains("FAILED"));
        assertTrue(diagnostic.contains("IllegalStateException"));
    }

    @Test
    void should_maskCredentialPlaintext_when_mcpRegistrationDiagnostic_given_causeEchoingToken() {
        // given（第三方服务器错误消息可能回显请求内容——诊断面须先脱敏再落日志）
        McpServerRegistrationResult failed = McpServerRegistrationResult.failed(
                "github", "streamable-http",
                new IllegalStateException("401 Unauthorized (Authorization: Bearer ghp_plain-token)"));

        // when
        String diagnostic = factory.mcpRegistrationDiagnostic(failed);

        // then（Bearer 形态明文被掩码：诊断不含可复用的凭证明文）
        assertFalse(diagnostic.contains("ghp_plain-token"));
        assertTrue(diagnostic.contains("Bearer ***"));
    }

    @Test
    void should_buildAskRuleWithRuntimeName_when_buildPermissionContext_given_alwaysAskPolicy() {
        // given（契约名 Bash 配置 always_ask——修复前以契约名造规则永不命中）
        List<AgentAssemblySpec.ToolExecutionPolicy> policies = List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("Bash", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK));

        // when
        PermissionContextState context = factory.buildPermissionContext(policies);

        // then（ASK 规则按运行时实名 execute 命中；BYPASS 模式兜底放行；不产生 DENY 规则）
        assertNotNull(context);
        assertEquals(PermissionMode.BYPASS, context.getMode());
        assertTrue(context.getAskRules().containsKey("execute"));
        assertEquals(PermissionBehavior.ASK, context.getAskRules().get("execute").get(0).behavior());
        assertTrue(context.getDenyRules().isEmpty());
    }

    @Test
    void should_buildDenyRuleWithRuntimeName_when_buildPermissionContext_given_alwaysDenyPolicy() {
        // given（契约名 Read 配置 always_deny）
        List<AgentAssemblySpec.ToolExecutionPolicy> policies = List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("Read", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_DENY));

        // when
        PermissionContextState context = factory.buildPermissionContext(policies);

        // then（DENY 规则按运行时实名 read_file 命中）
        assertNotNull(context);
        assertTrue(context.getDenyRules().containsKey("read_file"));
        assertEquals(PermissionBehavior.DENY, context.getDenyRules().get("read_file").get(0).behavior());
        assertTrue(context.getAskRules().isEmpty());
    }

    @Test
    void should_buildRuleWithMcpRuntimeName_when_buildPermissionContext_given_mcpToolsetPolicy() {
        // given（D16：mcp_toolset.configs[].name 为服务端原始工具名 get_weather + 服务器 weather）
        List<AgentAssemblySpec.ToolExecutionPolicy> policies = List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("get_weather",
                        AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK, "weather"));

        // when
        PermissionContextState context = factory.buildPermissionContext(policies);

        // then（规则按运行时实名 mcp__weather__get_weather 落表——按原始工具名落表永不命中）
        assertNotNull(context);
        assertEquals(Set.of("mcp__weather__get_weather"), context.getAskRules().keySet());
        assertFalse(context.getAskRules().containsKey("get_weather"));
        assertTrue(context.getDenyRules().isEmpty());
    }

    @Test
    void should_keepOtherRulesIntact_when_buildPermissionContext_given_configNameOutsideEnumeration() {
        // given（一项在枚举结果中、一项不在枚举结果中：后者按实名落表但对不存在的工具无效果）
        List<AgentAssemblySpec.ToolExecutionPolicy> policies = List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("get_weather",
                        AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK, "weather"),
                new AgentAssemblySpec.ToolExecutionPolicy("ghost_tool",
                        AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_DENY, "weather"));

        // when（不抛异常、不阻断装配）
        PermissionContextState context = factory.buildPermissionContext(policies);

        // then（两条规则各自独立；未枚举到的配置项不会污染其他工具）
        assertNotNull(context);
        assertEquals(Set.of("mcp__weather__get_weather"), context.getAskRules().keySet());
        assertEquals(Set.of("mcp__weather__ghost_tool"), context.getDenyRules().keySet());
    }

    @Test
    void should_skipUnmappedContractNames_when_buildPermissionContext_given_mixedPolicies() {
        // given（可映射 ask + 可映射 deny + 无运行时实体的契约名混合）
        List<AgentAssemblySpec.ToolExecutionPolicy> policies = List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("Bash", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK),
                new AgentAssemblySpec.ToolExecutionPolicy("Glob", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_DENY),
                new AgentAssemblySpec.ToolExecutionPolicy("ImageGen", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK));

        // when
        PermissionContextState context = factory.buildPermissionContext(policies);

        // then（ImageGen 静默跳过不产生规则，其余按实名落表；WebFetch 2.0.3 起已可映射故不再用作反例）
        assertNotNull(context);
        assertEquals(Set.of("execute"), context.getAskRules().keySet());
        assertEquals(Set.of("glob_files"), context.getDenyRules().keySet());
    }

    @Test
    void should_returnNullPermissionContext_when_buildPermissionContext_given_noEffectiveAskOrDenyRule() {
        // given / when / then（空策略、仅 always_allow、ask/deny 全为无实体契约名 → 均不启用权限引擎）
        assertNull(factory.buildPermissionContext(null));
        assertNull(factory.buildPermissionContext(List.of()));
        assertNull(factory.buildPermissionContext(List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("Bash", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ALLOW))));
        assertNull(factory.buildPermissionContext(List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("ImageGen", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK))));
    }

    // ==================== 系统提示挂载清单段落（D14） ====================

    /** 携带挂载视图与任意系统提示 / AGENTS.md 的装配规格（其余参数与 specWith 基线一致）。 */
    private static AgentAssemblySpec specWithFileMounts(List<AgentAssemblySpec.FileMountRef> fileMounts,
                                                        String systemPrompt, String agentsMd) {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(systemPrompt, agentsMd))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), List.of(), Map.of(), fileMounts))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(), null))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sess_toolkit_1", 7L))
                .build();
    }

    @Test
    void should_keepPromptUnchanged_when_buildSysPrompt_given_noFileMounts() {
        // given（无挂载视图：MUST NOT 拼空清单段落）
        AgentAssemblySpec spec = specWithFileMounts(List.of(), "台账系统提示词", null);

        // when
        String prompt = AgentscopeHarnessAgentFactory.buildSysPrompt(spec);

        // then（原样返回，不含标题）
        assertEquals("台账系统提示词", prompt);
        assertTrue(!prompt.contains("会话挂载文件"));
    }

    @Test
    void should_appendMountsSectionInOrder_when_buildSysPrompt_given_twoFileMounts() {
        // given（两项挂载：清单逐项一行、保 reconcile 视图顺序，含只读与交付通道引导）
        AgentAssemblySpec spec = specWithFileMounts(List.of(
                new AgentAssemblySpec.FileMountRef("file_1", "sales.csv", 2048L, "mounts/file_1"),
                new AgentAssemblySpec.FileMountRef("file_2", "notes.md", 10L, "mounts/docs/notes.md")),
                "台账系统提示词", null);

        // when
        String prompt = AgentscopeHarnessAgentFactory.buildSysPrompt(spec);

        // then（台账原文在前 + 标题 + 两行清单保序 + 交付引导）
        assertTrue(prompt.startsWith("台账系统提示词"));
        assertTrue(prompt.contains("## 会话挂载文件（只读）"));
        String line1 = "- sales.csv（2048 字节）→ /workspace/mounts/file_1（只读）";
        String line2 = "- notes.md（10 字节）→ /workspace/mounts/docs/notes.md（只读）";
        assertTrue(prompt.contains(line1) && prompt.contains(line2));
        assertTrue(prompt.indexOf(line1) < prompt.indexOf(line2));
        assertTrue(prompt.contains("deliver_artifact"));
    }

    // ==================== bind mount 工作区规格（D8 / D18） ====================

    @Test
    void should_skipWorkspaceSpec_when_buildMountsWorkspaceSpec_given_noFileMounts() {
        // given（无挂载视图：不探测目录、不下发条目，保持框架默认布局）
        AgentAssemblySpec spec = specWithFileMounts(List.of(), null, null);

        // when / then
        assertNull(factory.buildMountsWorkspaceSpec(spec));
        verifyNoInteractions(sessionWorkspacePort);
    }

    @Test
    void should_returnNull_when_buildMountsWorkspaceSpec_given_missingOrEmptyMountsDirectory(
            @TempDir Path tempDir) throws IOException {
        // given（视图有项但宿主目录缺失 / 为空：等价「沙箱内无挂载」）
        AgentAssemblySpec spec = specWithFileMounts(List.of(
                new AgentAssemblySpec.FileMountRef("file_1", "sales.csv", 2048L, "mounts/file_1")),
                null, null);
        Path missing = tempDir.resolve("missing");
        Path empty = tempDir.resolve("empty");
        Files.createDirectory(empty);
        when(sessionWorkspacePort.mountsDirectory("agent-a", "sess_toolkit_1"))
                .thenReturn(missing, empty);

        // when / then（缺失与空目录两态均不下发）
        assertNull(factory.buildMountsWorkspaceSpec(spec));
        assertNull(factory.buildMountsWorkspaceSpec(spec));
    }

    @Test
    void should_buildSingleReadOnlyMountsEntry_when_buildMountsWorkspaceSpec_given_nonEmptyDirectory(
            @TempDir Path tempDir) throws IOException {
        // given（目录非空：唯一 key=mounts 条目、绝对路径、只读恒定，工作区根不改写）
        AgentAssemblySpec spec = specWithFileMounts(List.of(
                new AgentAssemblySpec.FileMountRef("file_1", "sales.csv", 2048L, "mounts/file_1")),
                null, null);
        Path mountsDir = tempDir.resolve("sess_toolkit_1").resolve("mounts");
        Files.createDirectories(mountsDir);
        Files.createFile(mountsDir.resolve("file_1"));
        when(sessionWorkspacePort.mountsDirectory("agent-a", "sess_toolkit_1")).thenReturn(mountsDir);

        // when
        WorkspaceSpec workspaceSpec = factory.buildMountsWorkspaceSpec(spec);

        // then
        assertNotNull(workspaceSpec);
        assertEquals("/workspace", workspaceSpec.getRoot());
        assertEquals(Set.of("mounts"), workspaceSpec.getEntries().keySet());
        WorkspaceEntry entry = workspaceSpec.getEntries().get("mounts");
        assertTrue(entry instanceof BindMountEntry);
        BindMountEntry bind = (BindMountEntry) entry;
        assertEquals(mountsDir.toAbsolutePath().normalize().toString(), bind.getHostPath());
        assertTrue(bind.isReadOnly());
    }

    @Test
    void should_keepSessionsIsolated_when_buildMountsWorkspaceSpec_given_twoSessionsOfSameAgent(
            @TempDir Path tempDir) throws IOException {
        // given（同 Agent 两会话各自的挂载目录：hostPath 恒取本会话目录，互不泄露）
        Path dirA = Files.createDirectories(tempDir.resolve("sessA").resolve("mounts"));
        Path dirB = Files.createDirectories(tempDir.resolve("sessB").resolve("mounts"));
        Files.createFile(dirA.resolve("file_1"));
        Files.createFile(dirB.resolve("file_2"));
        when(sessionWorkspacePort.mountsDirectory("agent-a", "sessA")).thenReturn(dirA);
        when(sessionWorkspacePort.mountsDirectory("agent-a", "sessB")).thenReturn(dirB);
        AgentAssemblySpec specA = AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), List.of(), Map.of(),
                        List.of(new AgentAssemblySpec.FileMountRef("file_1", "a.csv", 1L, "mounts/file_1"))))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(), null))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sessA", 7L))
                .build();
        AgentAssemblySpec specB = AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(
                        List.of(), List.of(), List.of(), Map.of(),
                        List.of(new AgentAssemblySpec.FileMountRef("file_2", "b.csv", 1L, "mounts/file_2"))))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(), null))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sessB", 7L))
                .build();

        // when
        WorkspaceSpec wsA = factory.buildMountsWorkspaceSpec(specA);
        WorkspaceSpec wsB = factory.buildMountsWorkspaceSpec(specB);

        // then（各指向本会话命名空间，交叉不污染）
        assertEquals(dirA.toAbsolutePath().normalize().toString(),
                ((BindMountEntry) wsA.getEntries().get("mounts")).getHostPath());
        assertEquals(dirB.toAbsolutePath().normalize().toString(),
                ((BindMountEntry) wsB.getEntries().get("mounts")).getHostPath());
    }

    // ==================== AGENTS.md 物化（D4：工作区根恒 per-agent） ====================

    @Test
    void should_writeAgentsMdToAgentWorkspaceRoot_when_materializeAgentsMd_given_configured(
            @TempDir Path tempDir) throws IOException {
        // given（经端口取 Agent 工作区根，会话命名空间不参与）
        AgentAssemblySpec spec = specWithFileMounts(List.of(), null, "# 项目约定");
        Path agentWorkspace = tempDir.resolve("agent-a");
        when(sessionWorkspacePort.agentWorkspace("agent-a")).thenReturn(agentWorkspace);

        // when
        Path workspace = ReflectionTestUtils.invokeMethod(factory, "materializeAgentsMd", spec);

        // then（返回 per-agent 根并物化文件，不落入会话子目录）
        assertSame(agentWorkspace, workspace);
        assertEquals("# 项目约定",
                Files.readString(agentWorkspace.resolve("AGENTS.md"), StandardCharsets.UTF_8));
    }

    @Test
    void should_returnNullWithoutPortCall_when_materializeAgentsMd_given_notConfigured() {
        // given（未配置 AGENTS.md：不触碰端口、保持框架默认解析）
        AgentAssemblySpec spec = specWithFileMounts(List.of(), null, null);

        // when
        Path workspace = ReflectionTestUtils.invokeMethod(factory, "materializeAgentsMd", spec);

        // then
        assertNull(workspace);
        verifyNoInteractions(sessionWorkspacePort);
    }

    @Test
    void should_degradeToNoWorkspace_when_materializeAgentsMd_given_portRejectsEscape() {
        // given（端口逃逸防护抛 IllegalArgumentException：记日志返回 null，不阻断本轮）
        AgentAssemblySpec spec = specWithFileMounts(List.of(), null, "# 项目约定");
        when(sessionWorkspacePort.agentWorkspace("agent-a"))
                .thenThrow(new IllegalArgumentException("工作区路径越界"));

        // when / then
        assertNull(ReflectionTestUtils.invokeMethod(factory, "materializeAgentsMd", spec));
    }

    @Test
    void should_carrySessionEnvWithoutCredentialPlaintext_when_sandboxEnvironment_given_mountedVaultCredential() {
        // given（会话级环境变量 + 本轮挂载保管库凭证明文：凭证 MUST NOT 进入沙箱数据面）
        String token = "ghp_sandbox-must-never-see-this";
        AgentAssemblySpec spec = AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(List.of(), List.of(),
                        List.of(new AgentAssemblySpec.VaultCredentialRef(
                                "vault_1", "cr_1", "static_bearer", "https://mcp.example.com/mcp", token)),
                        Map.of("LOG_LEVEL", "info"), List.of()))
                .build();

        // when（装配产出的沙箱数据面环境变量）
        @SuppressWarnings("unchecked")
        Map<String, String> sandboxEnv =
                (Map<String, String>) ReflectionTestUtils.invokeMethod(factory, "sandboxEnvironment", spec);

        // then（仅会话级环境变量；凭证明文与任何 Vault 形态键零出现——凭据唯一出口是 MCP 请求头）
        assertNotNull(sandboxEnv);
        assertEquals(Map.of("LOG_LEVEL", "info"), sandboxEnv);
        assertTrue(sandboxEnv.keySet().stream().noneMatch(key -> key.toUpperCase().contains("VAULT")),
                sandboxEnv.toString());
        assertTrue(sandboxEnv.values().stream().noneMatch(value -> value.contains(token)),
                sandboxEnv.toString());
    }
}
