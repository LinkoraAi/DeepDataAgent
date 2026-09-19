package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.AgentIdentity;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.FileMountRef;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ModelBinding;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.MountView;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.PromptContent;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ToolExecutionPolicy;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ToolGovernance;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.ToolVisibility;
import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec.VaultCredentialRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.linkroa.deepdataagent.runtime.domain.model.AssemblySpecFixtures.baseBuilder;
import static com.linkroa.deepdataagent.runtime.domain.model.AssemblySpecFixtures.identity;
import static com.linkroa.deepdataagent.runtime.domain.model.AssemblySpecFixtures.model;
import static com.linkroa.deepdataagent.runtime.domain.model.AssemblySpecFixtures.mounts;
import static com.linkroa.deepdataagent.runtime.domain.model.AssemblySpecFixtures.noGovernance;
import static com.linkroa.deepdataagent.runtime.domain.model.AssemblySpecFixtures.ownership;
import static com.linkroa.deepdataagent.runtime.domain.model.AssemblySpecFixtures.prompt;

/**
 * {@link AgentAssemblySpec}（分组 record + Builder）与其分组子 record 的不变量单测（R13 / D1）。
 * <p>覆盖：顶层扁平访问器委托分组、分组紧凑构造器不变量、空分组拒绝、toString 脱敏面
 * （凭证 / 保管库 / AGENTS.md / 环境变量值均不泄露）。</p>
 */
class AgentAssemblySpecTest {

    @Test
    void should_buildSpec_when_builder_given_validGroups() {
        // given
        ModelBinding modelBinding = model("dashscope:qwen-plus", "sk-cred", "https://api.example.com/v1", null, null);

        // when
        AgentAssemblySpec spec = baseBuilder()
                .withModelBinding(modelBinding)
                .build();

        // then（扁平访问器委托分组读取）
        assertEquals("agent-a", spec.agentId());
        assertEquals("dashscope:qwen-plus", spec.model());
        assertEquals(20, spec.maxIters());
        assertEquals("ubuntu:22.04", spec.sandbox().image());
        assertEquals("sk-cred", spec.credential());
        assertEquals("https://api.example.com/v1", spec.apiEndpointUrl());
    }

    @Test
    void should_holdModelTuning_when_builder_given_tuningFields() {
        // given（生效调优参数随分组承载，供工厂注入 Harness 模型配置）
        ModelBinding modelBinding = model("dashscope:qwen-plus", null, null, "high", 200000);

        // when
        AgentAssemblySpec spec = baseBuilder().withModelBinding(modelBinding).build();

        // then
        assertEquals("high", spec.modelEffort());
        assertEquals(200000, spec.modelContextWindow());
    }

    @Test
    void should_defaultTuningToNull_when_builder_given_baseBuilderOnly() {
        // given / when（基线 Builder：模型绑定不含调优参数）
        AgentAssemblySpec spec = baseBuilder().build();

        // then
        assertNull(spec.modelEffort());
        assertNull(spec.modelContextWindow());
    }

    @Test
    void should_throw_when_constructModelBinding_given_nonPositiveContextWindow() {
        // given / when & then（分组不变量：上下文窗口非空须 >0）
        assertThrows(IllegalArgumentException.class,
                () -> new ModelBinding("m", null, null, "high", 0));
    }

    @Test
    void should_holdAgentsMd_when_builder_given_agentsMd() {
        // given（AGENTS.md 原文随提示词分组承载，供工厂物化到工作区根）
        PromptContent promptContent = prompt("你是助手", "# 项目约定\n\n统一使用中文回复。");

        // when
        AgentAssemblySpec spec = baseBuilder().withPromptContent(promptContent).build();

        // then
        assertEquals("# 项目约定\n\n统一使用中文回复。", spec.agentsMd());
    }

    @Test
    void should_keepSystemPromptAndAgentsMdIndependent_when_builder_given_both() {
        // given（系统提示词与 AGENTS.md 为两个正交槽位，互不覆盖）
        PromptContent promptContent = prompt("你是助手", "---\nname: 台账\ncode_interpreter\n---");

        // when
        AgentAssemblySpec spec = baseBuilder().withPromptContent(promptContent).build();

        // then
        assertEquals("你是助手", spec.systemPrompt());
        assertEquals("---\nname: 台账\ncode_interpreter\n---", spec.agentsMd());
    }

    @Test
    void should_throw_when_constructAgentIdentity_given_blankAgentId() {
        // given / when & then
        assertThrows(IllegalArgumentException.class,
                () -> identity("", "数据分析Agent"));
    }

    @Test
    void should_throw_when_constructAgentIdentity_given_blankName() {
        // given / when & then
        assertThrows(IllegalArgumentException.class,
                () -> identity("agent-a", " "));
    }

    @Test
    void should_throw_when_constructAgentIdentity_given_nameTooLong() {
        // given / when & then
        assertThrows(IllegalArgumentException.class,
                () -> identity("agent-a", "n".repeat(129)));
    }

    @Test
    void should_throw_when_constructModelBinding_given_blankModel() {
        // given / when & then
        assertThrows(IllegalArgumentException.class,
                () -> new ModelBinding(" ", null, null, null, null));
    }

    @Test
    void should_throw_when_build_given_nonPositiveMaxIters() {
        // given / when & then（顶层标量不变量：迭代上限须为正数）
        assertThrows(IllegalArgumentException.class,
                () -> baseBuilder().withMaxIters(0).build());
    }

    @Test
    void should_throw_when_constructPromptContent_given_oversizedAgentsMd() {
        // given / when & then（AGENTS.md 超出规格上限 20000 字符）
        assertThrows(IllegalArgumentException.class,
                () -> prompt("你是助手", "m".repeat(20001)));
    }

    @Test
    void should_throw_when_builder_given_nullSandbox() {
        // given / when & then（顶层拒绝空沙箱）
        assertThrows(IllegalArgumentException.class,
                () -> baseBuilder().withSandbox(null).build());
    }

    @Test
    void should_throw_when_builder_given_nullIdentity() {
        // given / when & then（顶层拒绝空分组）
        assertThrows(IllegalArgumentException.class,
                () -> baseBuilder().withIdentity(null).build());
    }

    @Test
    void should_throw_when_constructSandbox_given_negativeMemory() {
        // given / when & then
        assertThrows(IllegalArgumentException.class,
                () -> AssemblySpecFixtures.sandbox("ubuntu:22.04", -1L, 2L));
    }

    @Test
    void should_throw_when_constructSandbox_given_negativeCpu() {
        // given / when & then
        assertThrows(IllegalArgumentException.class,
                () -> AssemblySpecFixtures.sandbox("ubuntu:22.04", 8L, 0L));
    }

    @Test
    void should_throw_when_constructSandbox_given_blankImage() {
        // given / when & then
        assertThrows(IllegalArgumentException.class,
                () -> AssemblySpecFixtures.sandbox(" ", 8L, 2L));
    }

    @Test
    void should_holdMemoryStoreRefs_when_builder_given_memoryStoreRefs() {
        // given
        MemoryStoreRef ref = new MemoryStoreRef("ms-1", "会话记忆");

        // when
        AgentAssemblySpec spec = baseBuilder()
                .withMountView(mounts(List.of(), List.of(ref), List.of(), Map.of(), List.of()))
                .build();

        // then
        assertEquals(List.of(ref), spec.memoryStoreRefs());
    }

    @Test
    void should_normalizeNullComponents_when_constructMountView_given_nulls() {
        // given / when（挂载视图紧凑构造器把 null 归一为空集合，消费方无需判空）
        MountView view = new MountView(null, null, null, null, null);

        // then
        assertTrue(view.skills().isEmpty());
        assertTrue(view.memoryStoreRefs().isEmpty());
        assertTrue(view.vaultCredentials().isEmpty());
        assertTrue(view.envVars().isEmpty());
        assertTrue(view.fileMounts().isEmpty());
    }

    @Test
    void should_maskCredential_when_toString_given_plainCredential() {
        // given（明文凭证不得随 toString 泄露）
        AgentAssemblySpec spec = baseBuilder()
                .withPromptContent(prompt("你是助手", "# 项目约定\n\n统一使用中文回复。"))
                .withModelBinding(model("dashscope:qwen-plus", "sk-plain-secret", "https://api.example.com/v1", null, null))
                .build();

        // when
        String text = spec.toString();

        // then
        assertFalse(text.contains("sk-plain-secret"));
        assertTrue(text.contains("sk-p****"));
        // AGENTS.md 全文不随日志输出，仅暴露长度
        assertFalse(text.contains("统一使用中文回复"));
        assertTrue(text.contains("agentsMdLength=17"));
    }

    @Test
    void should_maskVaultToken_when_toString_given_plainVaultToken() {
        // given（保管库解密明文不得随 toString 泄露，VaultCredentialRef 独立脱敏）
        VaultCredentialRef ref = new VaultCredentialRef(
                "vault-1", "cr-1", "static_bearer", "https://db.example.com", "vault-plain-secret");

        // when
        String text = ref.toString();

        // then
        assertFalse(text.contains("vault-plain-secret"));
        assertTrue(text.contains("token=****"));
    }

    @Test
    void should_holdEnvironmentVariables_when_builder_given_envVars() {
        // given（会话级环境变量随挂载分组承载，供工厂注入沙箱数据面）
        AgentAssemblySpec spec = baseBuilder()
                .withMountView(mounts(List.of(), List.of(), List.of(), Map.of("LOG_LEVEL", "debug"), List.of()))
                .build();

        // when / then
        assertEquals(Map.of("LOG_LEVEL", "debug"), spec.environmentVariables());
    }

    @Test
    void should_exposeEnvVarKeysOnly_when_toString_given_environmentVariables() {
        // given（环境变量值可能含敏感材料，toString 仅输出键名）
        AgentAssemblySpec spec = baseBuilder()
                .withMountView(mounts(List.of(), List.of(), List.of(), Map.of("DB_PASSWORD", "super-secret-value"), List.of()))
                .build();

        // when
        String text = spec.toString();

        // then（键可见、值不可见）
        assertTrue(text.contains("DB_PASSWORD"));
        assertFalse(text.contains("super-secret-value"));
    }

    @Test
    void should_throw_when_constructVaultCredentialRef_given_blankVaultId() {
        // given / when & then（保管库ID必填）
        assertThrows(IllegalArgumentException.class,
                () -> new VaultCredentialRef(" ", "cr-1", "static_bearer", "https://db.example.com", "token"));
    }

    @Test
    void should_throw_when_constructVaultCredentialRef_given_blankCredentialId() {
        // given / when & then（凭证ID必填）
        assertThrows(IllegalArgumentException.class,
                () -> new VaultCredentialRef("vault-1", " ", "static_bearer", "https://db.example.com", "token"));
    }

    @Test
    void should_throw_when_constructVaultCredentialRef_given_blankAuthType() {
        // given / when & then（鉴权类型必填）
        assertThrows(IllegalArgumentException.class,
                () -> new VaultCredentialRef("vault-1", "cr-1", " ", "https://db.example.com", "token"));
    }

    @Test
    void should_throw_when_constructVaultCredentialRef_given_blankTarget() {
        // given / when & then（凭证 Target 必填：URL Target 型为服务器 URL、变量名 Target 型为变量名）
        assertThrows(IllegalArgumentException.class,
                () -> new VaultCredentialRef("vault-1", "cr-1", "static_bearer", " ", "token"));
    }

    @Test
    void should_holdEnvironmentVariableTarget_when_constructVaultCredentialRef_given_secretNameTarget() {
        // given（environment_variable 型：target 承载变量名而非 URL，同样满足非空不变量）
        VaultCredentialRef ref = new VaultCredentialRef(
                "vault-1", "cr-1", "environment_variable", "MY_API_KEY", "env-secret");

        // when / then
        assertEquals("MY_API_KEY", ref.target());
        assertFalse(ref.toString().contains("env-secret"));
    }

    // ==================== MCP 连接声明值对象 ====================

    @Test
    void should_holdMcpConnections_when_builder_given_mcpConnections() {
        // given（版本声明的两路 MCP 连接：名称 + streamable-http 端点）
        AgentAssemblySpec.McpConnection first =
                new AgentAssemblySpec.McpConnection("github", "https://api.githubcopilot.com/mcp");
        AgentAssemblySpec.McpConnection second =
                new AgentAssemblySpec.McpConnection("db", "https://mcp.example.com/v1/mcp");

        // when
        AgentAssemblySpec spec = baseBuilder()
                .withMcpConnections(List.of(first, second))
                .build();

        // then（保持声明顺序）
        assertEquals(List.of(first, second), spec.mcpConnections());
    }

    @Test
    void should_defaultEmptyMcpConnections_when_builder_given_noMcpConnections() {
        // given / when（基线 Builder 未显式设置连接清单）
        AgentAssemblySpec spec = baseBuilder().build();

        // then（归一为空清单，等价「版本未声明 MCP」）
        assertTrue(spec.mcpConnections().isEmpty());
    }

    @Test
    void should_throw_when_constructMcpConnection_given_blankNameOrUrl() {
        // given / when & then（名称与 URL 均必填）
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAssemblySpec.McpConnection(" ", "https://mcp.example.com/mcp"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAssemblySpec.McpConnection("github", " "));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAssemblySpec.McpConnection(null, null));
    }

    @Test
    void should_holdToolPolicies_when_builder_given_policies() {
        // given（逐工具权限策略随治理分组承载，供工厂装配权限引擎）
        ToolExecutionPolicy ask = new ToolExecutionPolicy("Bash", ToolExecutionPolicy.POLICY_ALWAYS_ASK);
        ToolExecutionPolicy deny = new ToolExecutionPolicy("WebFetch", ToolExecutionPolicy.POLICY_ALWAYS_DENY);

        // when
        AgentAssemblySpec spec = baseBuilder()
                .withToolGovernance(AssemblySpecFixtures.governance(List.of(ask, deny), null))
                .build();

        // then（保持声明顺序）
        assertEquals(List.of(ask, deny), spec.toolPolicies());
    }

    @Test
    void should_normalizeEmptyToolPoliciesAndVisibility_when_constructToolGovernance_given_nulls() {
        // given / when（治理分组紧凑构造器把 null 归一为空策略 / 无约束可见性）
        ToolGovernance governance = new ToolGovernance(null, null);

        // then
        assertTrue(governance.toolPolicies().isEmpty());
        assertFalse(governance.toolVisibility().hasConstraint());
    }

    @Test
    void should_throw_when_constructToolExecutionPolicy_given_blankName() {
        // given / when & then（工具名必填）
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExecutionPolicy(" ", ToolExecutionPolicy.POLICY_ALWAYS_ASK));
    }

    @Test
    void should_throw_when_constructToolExecutionPolicy_given_unknownOrNullPolicy() {
        // given / when & then（策略词汇三值收敛，null 不允许——与契约 DTO 不同，本 BC 值对象策略必非空）
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExecutionPolicy("Bash", "ask"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExecutionPolicy("Bash", null));
    }

    @Test
    void should_mapPolicyToEvaluatedVocabulary_when_evaluatedPermissionOf_given_eachPolicy() {
        // given / when / then（D15：对外发布求值结果 allow/ask/deny，未配置策略与 always_allow 同判）
        assertEquals(ToolExecutionPolicy.EVALUATED_ALLOW,
                ToolExecutionPolicy.evaluatedPermissionOf(ToolExecutionPolicy.POLICY_ALWAYS_ALLOW));
        assertEquals(ToolExecutionPolicy.EVALUATED_ASK,
                ToolExecutionPolicy.evaluatedPermissionOf(ToolExecutionPolicy.POLICY_ALWAYS_ASK));
        assertEquals(ToolExecutionPolicy.EVALUATED_DENY,
                ToolExecutionPolicy.evaluatedPermissionOf(ToolExecutionPolicy.POLICY_ALWAYS_DENY));
        assertEquals(ToolExecutionPolicy.EVALUATED_ALLOW, ToolExecutionPolicy.evaluatedPermissionOf(null));
        assertEquals(ToolExecutionPolicy.EVALUATED_ALLOW, ToolExecutionPolicy.evaluatedPermissionOf(""));
    }

    @Test
    void should_holdArtifactContext_when_builder_given_sessionIdAndOwnerId() {
        // given（产出归属上下文由 Session 装配现场透传，交付工具登记用）
        AgentAssemblySpec spec = baseBuilder()
                .withArtifactOwnership(ownership("sess_artifact_1", 42L))
                .build();

        // when / then
        assertEquals("sess_artifact_1", spec.sessionId());
        assertEquals(42L, spec.ownerId());
    }

    @Test
    void should_defaultArtifactContextNull_when_builder_given_noOwnership() {
        // given / when（未设置归属分组：归属槽位允许为空，交付时由登记面拒绝）
        AgentAssemblySpec spec = baseBuilder().build();

        // then
        assertNull(spec.sessionId());
        assertNull(spec.ownerId());
    }

    @Test
    void should_includeArtifactContext_when_toString_given_sessionIdAndOwnerId() {
        // given（归属上下文非敏感标识，随 toString 输出便于装配日志排查）
        AgentAssemblySpec spec = baseBuilder()
                .withModelBinding(model("dashscope:qwen-plus", "sk-secret-credential", null, null, null))
                .withArtifactOwnership(ownership("sess_log_2", 9L))
                .build();

        // when
        String text = spec.toString();

        // then（归属可见，凭证仍脱敏）
        assertTrue(text.contains("sess_log_2"));
        assertTrue(text.contains("ownerId=9"));
        assertFalse(text.contains("sk-secret-credential"));
    }

    @Test
    void should_holdToolVisibility_when_builder_given_visibility() {
        // given（可见性约束随治理分组承载，工厂据此产生工具过滤指令）
        ToolVisibility visibility = new ToolVisibility(List.of("Bash", "Read"), List.of("Write"));

        // when
        AgentAssemblySpec spec = baseBuilder()
                .withToolGovernance(AssemblySpecFixtures.governance(List.of(), visibility))
                .build();

        // then（原样承载契约名名单）
        assertEquals(visibility, spec.toolVisibility());
        assertTrue(spec.toolVisibility().hasConstraint());
    }

    @Test
    void should_defaultNoGovernance_when_builder_given_noGovernanceGroup() {
        // given / when（基线 Builder 使用空治理分组）
        AgentAssemblySpec spec = baseBuilder().build();

        // then
        assertFalse(spec.toolVisibility().hasConstraint());
        assertTrue(spec.toolPolicies().isEmpty());
    }

    @Test
    void should_normalizeLists_when_constructToolVisibility_given_nullOrPartialNames() {
        // given / when（null 名单归一为空名单；hasConstraint 当且仅当任一名单非空）
        ToolVisibility empty = new ToolVisibility(null, null);
        ToolVisibility hiddenOnly = new ToolVisibility(null, List.of("Write"));

        // then
        assertEquals(List.of(), empty.allowedTools());
        assertEquals(List.of(), empty.hiddenTools());
        assertFalse(empty.hasConstraint());
        assertTrue(hiddenOnly.hasConstraint());
        assertEquals(List.of("Write"), hiddenOnly.hiddenTools());
    }

    @Test
    void should_holdFileMounts_when_builder_given_fileMounts() {
        // given（文件挂载视图随挂载分组承载，工厂据此拼清单段落与 bind mount）
        FileMountRef mount = new FileMountRef("file_1", "sales.csv", 2048L, "mounts/file_1");

        // when
        AgentAssemblySpec spec = baseBuilder()
                .withMountView(mounts(List.of(), List.of(), List.of(), Map.of(), List.of(mount)))
                .build();

        // then
        assertEquals(List.of(mount), spec.fileMounts());
    }

    @Test
    void should_delegateEachAccessor_when_builder_given_allGroupsPopulated() {
        // given（逐分组装配后，扁平访问器应各自读到对应分组字段，覆盖全部 20 个旧字段位）
        MemoryStoreRef store = new MemoryStoreRef("ms-1", "会话记忆");
        VaultCredentialRef vault = new VaultCredentialRef(
                "vault-1", "cr-1", "static_bearer", "https://db.example.com", "sk-plain");
        FileMountRef mount = new FileMountRef("file_1", "sales.csv", 2048L, "mounts/file_1");
        ToolExecutionPolicy policy = new ToolExecutionPolicy("Bash", ToolExecutionPolicy.POLICY_ALWAYS_ASK);
        ToolVisibility visibility = new ToolVisibility(List.of("Bash"), List.of());

        // when
        AgentAssemblySpec spec = AgentAssemblySpec.builder()
                .withIdentity(identity("agent-a", "台账助手"))
                .withModelBinding(model("openai:gpt-4", "sk-plain", "https://api.example.com/v1", "high", 200000))
                .withPromptContent(prompt("你是助手", "# 约定"))
                .withMaxIters(10)
                .withSandbox(AssemblySpecFixtures.sandbox("python:3.12", 8192L, 4L))
                .withMountView(mounts(List.of(new Skill("s1", "技能", "正文", Map.of())),
                        List.of(store), List.of(vault), Map.of("K", "V"), List.of(mount)))
                .withToolGovernance(AssemblySpecFixtures.governance(List.of(policy), visibility))
                .withArtifactOwnership(ownership("sess_1", 7L))
                .build();

        // then（正常流程：全部分组字段经扁平访问器可读回）
        assertEquals("agent-a", spec.agentId());
        assertEquals("台账助手", spec.name());
        assertEquals("openai:gpt-4", spec.model());
        assertEquals("sk-plain", spec.credential());
        assertEquals("https://api.example.com/v1", spec.apiEndpointUrl());
        assertEquals("high", spec.modelEffort());
        assertEquals(200000, spec.modelContextWindow());
        assertEquals("你是助手", spec.systemPrompt());
        assertEquals("# 约定", spec.agentsMd());
        assertEquals(10, spec.maxIters());
        assertEquals("python:3.12", spec.sandbox().image());
        assertEquals(1, spec.skills().size());
        assertEquals(List.of(store), spec.memoryStoreRefs());
        assertEquals(List.of(vault), spec.vaultCredentials());
        assertEquals(Map.of("K", "V"), spec.environmentVariables());
        assertEquals(List.of(mount), spec.fileMounts());
        assertEquals(List.of(policy), spec.toolPolicies());
        assertEquals(visibility, spec.toolVisibility());
        assertEquals("sess_1", spec.sessionId());
        assertEquals(7L, spec.ownerId());
    }
}
