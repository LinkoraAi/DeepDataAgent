package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.application.dto.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.application.dto.ResolvedModelCredentialDTO;
import com.linkroa.deepdataagent.agent.application.service.ModelCatalogService;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.ModelCatalogItem;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelEffort;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.infrastructure.util.ModelCredentialEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.skill.api.SkillAssetApi;
import com.linkroa.deepdataagent.skill.api.dto.SkillContentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultAgentVersionAssemblyPort} 版本 + 模型解析端口单测。
 * <p>覆盖：发布号十进制解析、Agent 存在/归档校验、owner 归属隔离（agent / profile /
 * 版本号解析）、版本/profile 存在性、凭证解密链路、模型标识拼接
 * （AGENTSCOPE 原样，其余 format:modelName）、技能引用缺失显式失败。
 * 环境 / 记忆库 / 保管库挂载已移出版本快照（Session 执行时选择），装配契约对应槽位恒为空。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultAgentVersionAssemblyPortTest {

    /** 当前请求用户（与 stub 台账 owner 一致）。 */
    private static final Long OWNER = 1L;
    /** 越权场景下的他人 owner。 */
    private static final Long OTHER_OWNER = 2L;
    /** 钉版技能版本键（epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641129";

    @Mock private AgentDefinitionRepository agentDefinitionRepository;
    @Mock private AgentVersionRepository agentVersionRepository;
    @Mock private ModelProfileRepository modelProfileRepository;
    @Mock private ModelCatalogService modelCatalogService;
    @Mock private ModelCredentialEncryptionUtil credentialEncryptionUtil;
    @Mock private SkillAssetApi skillAssetApi;

    private DefaultAgentVersionAssemblyPort port;

    @BeforeEach
    void setUp() {
        port = new DefaultAgentVersionAssemblyPort();
        ReflectionTestUtils.setField(port, "agentDefinitionRepository", agentDefinitionRepository);
        ReflectionTestUtils.setField(port, "agentVersionRepository", agentVersionRepository);
        ReflectionTestUtils.setField(port, "modelProfileRepository", modelProfileRepository);
        ReflectionTestUtils.setField(port, "modelCatalogService", modelCatalogService);
        ReflectionTestUtils.setField(port, "credentialEncryptionUtil", credentialEncryptionUtil);
        ReflectionTestUtils.setField(port, "skillAssetApi", skillAssetApi);
    }

    @Test
    void should_resolveAssembly_when_resolve_given_validAgentAndVersion() {
        // given
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 2))
                .thenReturn(Optional.of(version(2, "v2", "你是数据分析专家", "p-1")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "2", OWNER);

        // then（发布号十进制解析 → 版本快照 + 模型配置，凭证在基础设施层解密）
        assertEquals("agent-a", resolved.agentId());
        assertEquals(2, resolved.versionNumber());
        assertEquals("v2", resolved.versionName());
        assertEquals("你是数据分析专家", resolved.sysPrompt());
        assertEquals("openai:gpt-4", resolved.modelIndicator());
        assertEquals(10, resolved.maxIters());
        assertEquals("sk-plain", resolved.credential());
        assertEquals("https://api.example.com/v1", resolved.apiEndpointUrl());
        assertEquals(0, resolved.skills().size());
        // AGENTS.md 能力已废止：装配侧不再读 agents_md，契约占位恒 null
        assertNull(resolved.agentsMd());
        // 环境 / 记忆库 / 保管库不再来自版本快照：装配恒为空位（由 Session 挂载装配填充）
        assertNull(resolved.environment());
        assertEquals(0, resolved.memoryStores().size());
        assertEquals(0, resolved.vaultCredentials().size());
    }

    @Test
    void should_resolveDynamicSkillBinding_when_resolve_given_bindingWithoutVersion() {
        // given（动态版绑定：version 省略 → 装配期按当时最新版本向 skill 资产解析）
        stubDefinition(false);
        AgentVersion versionRow = AgentVersion.restore(
                1L, "v-id-dyn", "agent-a", 1, "v1", null, "",
                "p-1", null, null, null, "[{\"type\":\"custom\",\"skill_id\":\"skill_1\"}]", null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionRow));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        when(skillAssetApi.findContent("skill_1", null, OWNER))
                .thenReturn(new SkillContentDTO("skill_1", EPOCH, "code-reviewer", "代码评审",
                        "code-reviewer", "review", Map.of()));

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then（动态版以 null 版本键解析，契约出已物化内容）
        assertEquals(1, resolved.skills().size());
        assertEquals("code-reviewer", resolved.skills().get(0).name());
        verify(skillAssetApi).findContent("skill_1", null, OWNER);
    }

    @Test
    void should_resolveSkills_when_resolve_given_versionWithMountedSkills() {
        // given（版本挂载 custom 技能绑定，按 skill_id + epoch 版本键经 skill 资产解析为装配契约）
        stubDefinition(false);
        AgentVersion versionRow = AgentVersion.restore(
                1L, "v-id-1", "agent-a", 1, "v1", null, "",
                "p-1", null, null, null,
                "[{\"type\":\"custom\",\"skill_id\":\"skill_1\",\"version\":\"1759178010641129\"}]", null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionRow));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        when(skillAssetApi.findContent("skill_1", EPOCH, OWNER))
                .thenReturn(new SkillContentDTO("skill_1", EPOCH, "code-reviewer", "代码评审",
                        "code-reviewer", "review", Map.of()));

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then（物化目录名取版本快照解析出的 directory，正文 / 资源来自 skill 资产）
        assertEquals(1, resolved.skills().size());
        assertEquals("code-reviewer", resolved.skills().get(0).dirName());
        assertEquals("code-reviewer", resolved.skills().get(0).name());
        assertEquals("代码评审", resolved.skills().get(0).description());
        assertEquals("review", resolved.skills().get(0).skillContent());
        assertEquals(0, resolved.skills().get(0).resources().size());
    }

    @Test
    void should_throwNotFound_when_resolve_given_nonDecimalReleaseNumber() {
        // given（发布号解析在仓储查询之前：无需 stub 台账查询）

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolve("agent-a", "v1", OWNER));
        assertEquals("发布号格式非法", ex.getMessage());
    }

    @Test
    void should_throwNotFound_when_resolve_given_blankReleaseNumber() {
        // given（空白发布号同上，在仓储查询前即拒绝）

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("agent-a", " ", OWNER));
    }

    @Test
    void should_throwNotFound_when_resolve_given_zeroReleaseNumber() {
        // given（发布号不能小于 1）

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("agent-a", "0", OWNER));
    }

    @Test
    void should_throwNotFound_when_resolve_given_missingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("ghost")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("ghost", "1", OWNER));
    }

    @Test
    void should_throwNotFound_when_resolve_given_archivedAgent() {
        // given
        stubDefinition(true);

        // when & then（归档 Agent 拒绝创建新会话，无回退）
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolve("agent-a", "1", OWNER));
        assertEquals("Agent已归档，不可创建新会话", ex.getMessage());
    }

    @Test
    void should_throwNotFound_when_resolve_given_agentOwnedByOtherUser() {
        // given（Agent 归属他人：与「不存在」同语义，404 不泄露存在性）
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null, null, 2, 2, OTHER_OWNER,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolve("agent-a", "1", OWNER));
        assertEquals("Agent不存在", ex.getMessage());
    }

    @Test
    void should_throwNotFound_when_resolve_given_missingVersion() {
        // given
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 9))
                .thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("agent-a", "9", OWNER));
    }

    @Test
    void should_throwNotFound_when_resolve_given_missingProfile() {
        // given
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(version(1, "v1", null, "p-missing")));
        when(modelProfileRepository.findByProfileId("p-missing")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("agent-a", "1", OWNER));
    }

    @Test
    void should_throwNotFound_when_resolve_given_profileOwnedByOtherUser() {
        // given（模型配置归属他人：与「不存在」同语义，404）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(version(1, "v1", null, "p-1")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profileWithOwner(ApiFormat.OPENAI, "gpt-4", "enc-cred", OTHER_OWNER)));

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolve("agent-a", "1", OWNER));
        assertEquals("模型配置不存在", ex.getMessage());
    }

    @Test
    void should_throwNotFound_when_resolve_given_missingSkillReference() {
        // given（技能引用缺失：装配显式失败，不做静默降级）
        stubDefinition(false);
        AgentVersion versionRow = AgentVersion.restore(
                1L, "v-id-5", "agent-a", 1, "v1", null, "",
                "p-1", null, null, null,
                "[{\"type\":\"custom\",\"skill_id\":\"skill_ghost\",\"version\":\"1759178010641129\"}]", null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionRow));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        when(skillAssetApi.findContent("skill_ghost", EPOCH, OWNER)).thenReturn(null);

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolve("agent-a", "1", OWNER));
        assertEquals("技能引用不存在: skill_ghost", ex.getMessage());
    }

    @Test
    void should_returnModelNameAsIndicator_when_resolve_given_agentscopeProfile() {
        // given（AGENTSCOPE：模型标识直接用注册表模型名，不加前缀）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(version(1, "v1", null, "p-as")));
        when(modelProfileRepository.findByProfileId("p-as"))
                .thenReturn(Optional.of(profile(ApiFormat.AGENTSCOPE, "dashscope:qwen-plus", null)));

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then
        assertEquals("dashscope:qwen-plus", resolved.modelIndicator());
    }

    @Test
    void should_resolveProfileViaCatalogMapping_when_resolve_given_snapshotProfileIdMissing() {
        // given（版本快照无 profileId：经目录供应商映射按目录模型 id 兜底解析内部配置）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithModel(1, "v1", "\"ultimate\"")));
        when(modelCatalogService.resolveProfileId("ultimate", OWNER)).thenReturn("p-map");
        when(modelProfileRepository.findByProfileId("p-map"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        when(modelCatalogService.find("ultimate")).thenReturn(Optional.empty());

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then（映射解析在装配期完成，快照 profileId 仓储查询不发生）
        assertEquals("sk-plain", resolved.credential());
        assertEquals("openai:gpt-4", resolved.modelIndicator());
        verify(modelProfileRepository, never()).findByProfileId("p-1");
    }

    @Test
    void should_throwNotFound_when_resolve_given_noInternalProviderMapping() {
        // given（快照 profileId 与目录映射皆缺失：装配显式失败，不静默切换供应商）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithModel(1, "v1", "\"ultimate\"")));
        when(modelCatalogService.resolveProfileId("ultimate", OWNER)).thenReturn(null);

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolve("agent-a", "1", OWNER));
        assertEquals("该模型未配置内部供应商映射", ex.getMessage());
    }

    @Test
    void should_preferExplicitTuning_when_resolve_given_modelRefWithEffortAndWindow() {
        // given（对象形态引用携带显式档位：显式值优先于目录默认）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithModel(1, "v1",
                        "{\"id\":\"ultimate\",\"effort\":\"high\",\"context_window\":200000}")));
        when(modelCatalogService.resolveProfileId("ultimate", OWNER)).thenReturn("p-map");
        when(modelProfileRepository.findByProfileId("p-map"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        when(modelCatalogService.find("ultimate")).thenReturn(Optional.of(catalogItem()));

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then（生效调优 = 显式档位，目录默认 medium/128000 被覆盖）
        assertEquals("high", resolved.modelEffort());
        assertEquals(200000, resolved.modelContextWindow());
    }

    @Test
    void should_fallbackToCatalogDefaults_when_resolve_given_shorthandModelRef() {
        // given（字符串简写引用无调优参数：目录默认档位生效）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithModel(1, "v1", "\"ultimate\"")));
        when(modelCatalogService.resolveProfileId("ultimate", OWNER)).thenReturn("p-map");
        when(modelProfileRepository.findByProfileId("p-map"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        when(modelCatalogService.find("ultimate")).thenReturn(Optional.of(catalogItem()));

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then（无显式档位时取目录 default_effort / default_context_window）
        assertEquals("medium", resolved.modelEffort());
        assertEquals(128000, resolved.modelContextWindow());
    }

    @Test
    void should_collectToolPolicies_when_resolve_given_toolsWithPermissionPolicy() {
        // given（tools_json 逐工具配置：仅 permission_policy 非空项进入装配契约，保持声明顺序）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithTools(1, "v1",
                        "[{\"type\":\"agent_toolset_20260401\",\"configs\":["
                                + "{\"name\":\"Bash\",\"permission_policy\":\"always_ask\"},"
                                + "{\"name\":\"Read\",\"permission_policy\":\"always_allow\"},"
                                + "{\"name\":\"Write\"}]},"
                                + "{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"db-mcp\",\"configs\":["
                                + "{\"name\":\"query\",\"permission_policy\":\"always_deny\"}]}]")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then（跨 toolset 统一展平；无策略的 Write 不产出契约项）
        assertEquals(3, resolved.toolPolicies().size());
        assertEquals("Bash", resolved.toolPolicies().get(0).name());
        assertEquals("always_ask", resolved.toolPolicies().get(0).permissionPolicy());
        assertEquals("always_allow", resolved.toolPolicies().get(1).permissionPolicy());
        assertEquals("query", resolved.toolPolicies().get(2).name());
        assertEquals("always_deny", resolved.toolPolicies().get(2).permissionPolicy());
    }

    @Test
    void should_returnEmptyToolPolicies_when_resolve_given_versionWithoutTools() {
        // given（无 tools_json 的版本：契约策略槽位为空列表）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(version(1, "v1", null, "p-1")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then
        assertEquals(0, resolved.toolPolicies().size());
    }

    // ==================== 工具可见性聚合 ====================

    @Test
    void should_aggregateToolVisibility_when_resolve_given_toolsetListsAndDisabledConfig() {
        // given（agent_toolset 三组可见性字段：白名单 / 黑名单 / 逐工具关闭）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithTools(1, "v1",
                        "[{\"type\":\"agent_toolset_20260401\","
                                + "\"enabled_tools\":[\"Bash\",\"Read\"],"
                                + "\"disallowed_tools\":[\"Write\"],"
                                + "\"configs\":[{\"name\":\"Glob\",\"enabled\":false}]}]")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then（enabled 并集；disallowed 与 enabled=false 并入隐藏，保持声明顺序）
        assertTrue(resolved.toolVisibility().hasConstraint());
        assertEquals(List.of("Bash", "Read"), resolved.toolVisibility().allowedTools());
        assertEquals(List.of("Write", "Glob"), resolved.toolVisibility().hiddenTools());
    }

    @Test
    void should_reviveDisallowedTool_when_resolve_given_configEnabledTrue() {
        // given（configs.enabled=true 末位复活：先并入隐藏再被 true 剔除）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithTools(1, "v1",
                        "[{\"type\":\"agent_toolset_20260401\","
                                + "\"disallowed_tools\":[\"Bash\"],"
                                + "\"configs\":[{\"name\":\"Bash\",\"enabled\":true}]}]")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then（复活后无残余约束）
        assertFalse(resolved.toolVisibility().hasConstraint());
        assertEquals(List.of(), resolved.toolVisibility().hiddenTools());
    }

    @Test
    void should_ignoreNonAgentToolset_when_resolve_given_mcpToolsetVisibilityFields() {
        // given（非 agent_toolset 条目不参与可见性聚合）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithTools(1, "v1",
                        "[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"db-mcp\",\"configs\":["
                                + "{\"name\":\"query\",\"enabled\":false}]}]")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then
        assertFalse(resolved.toolVisibility().hasConstraint());
    }

    @Test
    void should_unionAndDeduplicateAllowedTools_when_resolve_given_multipleAgentToolsets() {
        // given（两个 agent_toolset 条目：白名单并集去重保序）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionWithTools(1, "v1",
                        "[{\"type\":\"agent_toolset_20260401\",\"enabled_tools\":[\"Bash\",\"Read\"]},"
                                + "{\"type\":\"agent_toolset_20260401\",\"enabled_tools\":[\"Read\",\"Write\"]}]")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then
        assertEquals(List.of("Bash", "Read", "Write"), resolved.toolVisibility().allowedTools());
    }

    @Test
    void should_returnEmptyToolVisibility_when_resolve_given_versionWithoutTools() {
        // given（无 tools_json 的版本：可见性契约槽位为空名单归一化）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(version(1, "v1", null, "p-1")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1", OWNER);

        // then
        assertFalse(resolved.toolVisibility().hasConstraint());
        assertEquals(List.of(), resolved.toolVisibility().allowedTools());
        assertEquals(List.of(), resolved.toolVisibility().hiddenTools());
    }

    @Test
    void should_validateWithoutDecrypt_when_assertResolvable_given_validAgentAndVersion() {
        // given（轻量校验链路：发布号/Agent/版本/profile + owner 全部通过）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 2))
                .thenReturn(Optional.of(version(2, "v2", null, "p-1")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));

        // when
        port.assertResolvable("agent-a", "2", OWNER);

        // then（仅校验，不执行凭证解密）
        verify(credentialEncryptionUtil, never()).decrypt(any());
    }

    @Test
    void should_throwNotFound_when_assertResolvable_given_missingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("ghost")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.assertResolvable("ghost", "1", OWNER));
    }

    @Test
    void should_throwNotFound_when_assertResolvable_given_agentOwnedByOtherUser() {
        // given（越权装配校验：与「不存在」同语义，404）
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null, null, 2, 2, OTHER_OWNER,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.assertResolvable("agent-a", "1", OWNER));
    }

    // ==================== 凭证窄解析（slim-agent-assembly D2） ====================

    @Test
    void should_resolveModelCredentialOnly_when_resolveModelCredential_given_validAgentAndVersion() {
        // given（版本挂载 custom 技能绑定：窄解析 MUST NOT 触达技能资产）
        stubDefinition(false);
        AgentVersion versionRow = AgentVersion.restore(
                1L, "v-id-narrow", "agent-a", 1, "v1", null, "",
                "p-1", null, null, null,
                "[{\"type\":\"custom\",\"skill_id\":\"skill_1\",\"version\":\"1759178010641129\"}]", null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionRow));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");

        // when
        ResolvedModelCredentialDTO credential = port.resolveModelCredential("agent-a", "1", OWNER);

        // then（仅解密模型凭证 + 取 API 端点；不加载技能正文）
        assertEquals("sk-plain", credential.credential());
        assertEquals("https://api.example.com/v1", credential.apiEndpointUrl());
        verify(skillAssetApi, never()).findContent(any(), any(), any());
    }

    @Test
    void should_maskCredential_when_toString_given_resolvedModelCredential() {
        // given / when
        ResolvedModelCredentialDTO credential = new ResolvedModelCredentialDTO("sk-plain-secret", "https://api.example.com/v1");

        // then
        String text = credential.toString();
        assertFalse(text.contains("sk-plain-secret"));
        assertTrue(text.contains("sk-p****"));
    }

    @Test
    void should_throwNotFound_when_resolveModelCredential_given_agentOwnedByOtherUser() {
        // given（越权：命中路径每轮重跑归属校验，不匹配 → 404）
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null, null, 2, 2, OTHER_OWNER,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolveModelCredential("agent-a", "1", OWNER));
        assertEquals("Agent不存在", ex.getMessage());
        verify(credentialEncryptionUtil, never()).decrypt(any());
    }

    // ==================== 版本号解析 ====================

    @Test
    void should_returnActiveVersion_when_activeVersionNumber_given_existingAgent() {
        // given（active_version=2）
        stubDefinition(false);

        // when
        String version = port.activeVersionNumber("agent-a", OWNER);

        // then
        assertEquals("2", version);
    }

    @Test
    void should_throwNotFound_when_activeVersionNumber_given_noActiveVersion() {
        // given（尚未存在激活版本：active_version=0）
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null, null, 0, 0, 1L,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.activeVersionNumber("agent-a", OWNER));
    }

    @Test
    void should_throwNotFound_when_activeVersionNumber_given_agentOwnedByOtherUser() {
        // given（越权解析版本号：404 不泄露存在性）
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null, null, 2, 2, OTHER_OWNER,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.activeVersionNumber("agent-a", OWNER));
    }

    private void stubDefinition(boolean archived) {
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null,
                archived ? OffsetDateTime.now(ZoneId.of("Asia/Shanghai")) : null,
                2, 2, 1L, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));
    }

    private AgentVersion version(int versionNumber, String name, String sysPrompt, String profileId) {
        return AgentVersion.restore(
                1L, "v-id-" + versionNumber, "agent-a", versionNumber, name, null,
                sysPrompt != null ? sysPrompt : "", profileId, null,
                null, null, null, null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private AgentVersion versionWithTools(int versionNumber, String name, String toolsJson) {
        return AgentVersion.restore(
                1L, "v-tools-" + versionNumber, "agent-a", versionNumber, name, null,
                "", "p-1", null, toolsJson, null, null, null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private AgentVersion versionWithModel(int versionNumber, String name, String modelJson) {
        return AgentVersion.restore(
                1L, "v-model-" + versionNumber, "agent-a", versionNumber, name, null,
                "", null, modelJson, null, null, null, null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private ModelCatalogItem catalogItem() {
        return new ModelCatalogItem("ultimate", "旗舰模型", ModelCatalogItem.SOURCE_SYSTEM, true,
                false, false, false,
                List.of(ModelEffort.from("low"), ModelEffort.from("medium"), ModelEffort.from("high")),
                ModelEffort.from("medium"), 200000, 8000, 128000, List.of(128000, 200000));
    }

    private ModelProfile profile(ApiFormat apiFormat, String modelName, String encryptedCredential) {
        return profileWithOwner(apiFormat, modelName, encryptedCredential, OWNER);
    }

    private ModelProfile profileWithOwner(ApiFormat apiFormat, String modelName,
                                          String encryptedCredential, Long ownerId) {
        return ModelProfile.create(
                "p-1", "指令模型", null, apiFormat, "https://api.example.com/v1",
                modelName, encryptedCredential, null, null, null,
                10, ModelType.CHAT, null, ownerId);
    }
}

