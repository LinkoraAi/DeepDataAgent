package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliveryPort;
import com.linkroa.deepdataagent.runtime.domain.factory.SessionWorkspacePort;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.tool.WebTools;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 按 artifactDeliveryTarget 自动注册，不在平台 Toolkit 台账）、
 * 工具可见性约束的落地（框架侧仅 deny；内置白名单由平台侧
 * {@link AgentscopeHarnessAgentFactory#applyBuiltinWhitelist} 施加）、
 * 权限策略契约名→运行时实名规则构型、系统提示挂载清单段落的有无两态与保序（D14）、
 * bind mount 工作区规格的目录实况三态与只读条目构型（D8 / D18）、
 * AGENTS.md 恒物化到 per-agent 工作区根（D4）、沙箱数据面环境变量零凭据。</p>
 */
class AgentscopeHarnessAgentFactoryTest {

    private final SessionWorkspacePort sessionWorkspacePort = mock(SessionWorkspacePort.class);
    private final AgentscopeHarnessAgentFactory factory = newFactory();

    private AgentscopeHarnessAgentFactory newFactory() {
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory();
        ReflectionTestUtils.setField(factory, "artifactDeliveryPort",
                mock(ArtifactDeliveryPort.class));
        ReflectionTestUtils.setField(factory, "sessionWorkspacePort", sessionWorkspacePort);
        return factory;
    }

    /** 以真实框架工具实体装配台账（内置工具裁撤用例的观测对象）。 */
    private static Toolkit toolkitWith(Object... tools) {
        Toolkit toolkit = new Toolkit();
        for (Object tool : tools) {
            toolkit.registerTool(tool);
        }
        return toolkit;
    }

    private static AgentAssemblySpec specWith(AgentAssemblySpec.ToolVisibility toolVisibility) {
        return AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(List.of(), Map.of(), List.of()))
                .withToolGovernance(new AgentAssemblySpec.ToolGovernance(List.of(), toolVisibility))
                .withArtifactOwnership(new AgentAssemblySpec.ArtifactOwnership("sess_toolkit_1", 7L))
                .build();
    }

    @Test
    void should_registerOnlyTodoBaseline_when_buildToolkit_given_minimalSpec() {
        // given（最小规格）
        AgentAssemblySpec spec = specWith(null);

        // when
        Toolkit toolkit = factory.buildToolkit(spec);

        // then（平台 Toolkit 仅 TodoTools；交付工具由框架 build() 自动注册）
        Set<String> names = toolkit.getToolNames();
        assertTrue(names.contains(HarnessToolNames.TODO_WRITE));
        assertTrue(!names.contains("deliver_artifact"));
        assertTrue(!names.contains("DeliverArtifacts"));
    }

    @Test
    void should_defaultDenyWebTools_when_buildToolsConfig_given_noVisibilityConstraint() {
        // given（可见性约束为空名单 = 无约束；2.0.3 起框架无条件注册联网工具，平台默认关闭（D9））
        AgentAssemblySpec spec = specWith(new AgentAssemblySpec.ToolVisibility(null, null));

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
        AgentAssemblySpec spec = specWith(null);

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then：恒有主张——仍下发联网默认 deny
        assertNotNull(config);
        assertEquals(List.of("web_fetch", "web_search"), config.getDeny());
    }

    @Test
    void should_notSetAllowNorDeny_when_buildToolsConfig_given_allowlistOnly() {
        // given（白名单含可映射名——含 2.0.3 新注册实体的 WebFetch）
        AgentAssemblySpec spec = specWith(new AgentAssemblySpec.ToolVisibility(List.of("Bash", "Read", "WebFetch"), null));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（白名单不经框架 allow 下达，改由平台侧 applyBuiltinWhitelist 施加；
        //     白名单场景亦不设 deny：联网两名已被白名单天然排除）
        assertNotNull(config);
        assertNull(config.getAllow());
        assertNull(config.getDeny());
    }

    @Test
    void should_cropUnlistedBuiltinsAndKeepTodoBaseline_when_applyBuiltinWhitelist_given_allowlist() {
        // given（台账含内置 todo_write 与框架无条件注册的联网两名；白名单仅列 Read）
        Toolkit toolkit = toolkitWith(new TodoTools(), new WebTools.WebFetchTool(), new WebTools.WebSearchTool());
        AgentAssemblySpec spec = specWith(new AgentAssemblySpec.ToolVisibility(List.of("Read"), null));

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
        factory.applyBuiltinWhitelist(toolkit, specWith(null));

        // then（不介入：联网两名仍由 ToolsConfig.deny 治理）
        assertEquals(before, toolkit.getToolNames());
    }

    @Test
    void should_mergeDefaultWebDeny_when_buildToolsConfig_given_denylistOnly() {
        // given（仅黑名单约束：allow 不设 = 基座全量放行，deny = 翻译隐藏名单 + 联网默认两名）
        AgentAssemblySpec spec = specWith(new AgentAssemblySpec.ToolVisibility(null, List.of("Write", "Glob")));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（去重保序：翻译名单在前，联网两名并入其后）
        assertNotNull(config);
        assertNull(config.getAllow());
        assertEquals(List.of("write_file", "glob_files", "web_fetch", "web_search"), config.getDeny());
    }

    @Test
    void should_keepUserDenyWithoutWebMerge_when_buildToolsConfig_given_allowlistWithHiddenTools() {
        // given（白名单 + 黑名单并存：deny 承载用户显式隐藏名单，但不追加联网两名（白名单已排除））
        AgentAssemblySpec spec = specWith(new AgentAssemblySpec.ToolVisibility(List.of("Bash", "WebSearch"), List.of("Write")));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then：白名单不下发；deny 仅用户隐藏名单、无联网默认名
        assertNull(config.getAllow());
        assertEquals(List.of("write_file"), config.getDeny());
    }

    @Test
    void should_defaultDenyWebTools_when_buildToolsConfig_given_hiddenToolsAllUnmapped() {
        // given（隐藏名单在 Harness 2.0.3 仍全部无注册实体——ImageGen / ImageSearch，翻译后为空）
        AgentAssemblySpec spec = specWith(new AgentAssemblySpec.ToolVisibility(null, List.of("ImageGen", "ImageSearch")));

        // when
        ToolsConfig config = factory.buildToolsConfig(spec);

        // then（翻译名单为空仍下发联网默认 deny——恒有主张，不再有 null 语义）
        assertNotNull(config);
        assertEquals(List.of("web_fetch", "web_search"), config.getDeny());
    }

    // ==================== 权限策略契约名 → 运行时实名 ====================

    @Test
    void should_buildAskRuleWithRuntimeName_when_buildPermissionContext_given_alwaysAskPolicy() {
        // given（契约名 Bash 配置 always_ask）
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
    void should_skipUnmappedContractNames_when_buildPermissionContext_given_mixedPolicies() {
        // given（可映射 ask + 可映射 deny + 无运行时实体的契约名混合）
        List<AgentAssemblySpec.ToolExecutionPolicy> policies = List.of(
                new AgentAssemblySpec.ToolExecutionPolicy("Bash", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK),
                new AgentAssemblySpec.ToolExecutionPolicy("Glob", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_DENY),
                new AgentAssemblySpec.ToolExecutionPolicy("ImageGen", AgentAssemblySpec.ToolExecutionPolicy.POLICY_ALWAYS_ASK));

        // when
        PermissionContextState context = factory.buildPermissionContext(policies);

        // then（ImageGen 静默跳过不产生规则，其余按实名落表）
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
                .withMountView(new AgentAssemblySpec.MountView(List.of(), Map.of(), fileMounts))
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
    void should_carryOnlySessionEnvVars_when_sandboxEnvironmentGiven_sessionEnvVars() {
        // given（会话级环境变量：唯一进入沙箱数据面的来源，零凭据）
        AgentAssemblySpec spec = AgentAssemblySpec.builder()
                .withIdentity(new AgentAssemblySpec.AgentIdentity("agent-a", "台账助手"))
                .withModelBinding(new AgentAssemblySpec.ModelBinding("openai:gpt-4", null, null, null, null))
                .withPromptContent(new AgentAssemblySpec.PromptContent(null, null))
                .withMaxIters(10)
                .withSandbox(AgentAssemblySpec.Sandbox.of("python:3.12", 8192L, 4L))
                .withMountView(new AgentAssemblySpec.MountView(List.of(), Map.of("LOG_LEVEL", "info"), List.of()))
                .build();

        // when（装配产出的沙箱数据面环境变量）
        @SuppressWarnings("unchecked")
        Map<String, String> sandboxEnv =
                (Map<String, String>) ReflectionTestUtils.invokeMethod(factory, "sandboxEnvironment", spec);

        // then（仅会话级环境变量）
        assertNotNull(sandboxEnv);
        assertEquals(Map.of("LOG_LEVEL", "info"), sandboxEnv);
    }
}