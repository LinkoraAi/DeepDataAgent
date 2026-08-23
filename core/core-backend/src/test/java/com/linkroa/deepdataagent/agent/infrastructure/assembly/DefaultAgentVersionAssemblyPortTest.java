package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.SandboxSpec;
import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.agent.domain.model.SkillResourceManifest;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.domain.repository.SkillContentStore;
import com.linkroa.deepdataagent.agent.domain.repository.SkillRepository;
import com.linkroa.deepdataagent.agent.infrastructure.util.ModelCredentialEncryptionUtil;
import com.linkroa.deepdataagent.memory.api.MemoryStoreApi;
import com.linkroa.deepdataagent.memory.application.contract.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.vault.application.port.SecretResolutionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultAgentVersionAssemblyPort} 版本 + 模型解析端口单测。
 * <p>覆盖：发布号十进制解析、Agent 存在/归档校验、版本/profile 存在性、
 * 凭证解密链路、模型标识拼接（AGENTSCOPE 原样，其余 format:modelName）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultAgentVersionAssemblyPortTest {

    @Mock private AgentDefinitionRepository agentDefinitionRepository;
    @Mock private AgentVersionRepository agentVersionRepository;
    @Mock private ModelProfileRepository modelProfileRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private SkillContentStore skillContentStore;
    @Mock private ModelCredentialEncryptionUtil credentialEncryptionUtil;
    @Mock private EnvironmentRepository environmentRepository;
    @Mock private MemoryStoreApi memoryStoreApi;
    @Mock private SecretResolutionPort secretResolutionPort;

    private DefaultAgentVersionAssemblyPort port;

    @BeforeEach
    void setUp() {
        port = new DefaultAgentVersionAssemblyPort();
        ReflectionTestUtils.setField(port, "agentDefinitionRepository", agentDefinitionRepository);
        ReflectionTestUtils.setField(port, "agentVersionRepository", agentVersionRepository);
        ReflectionTestUtils.setField(port, "modelProfileRepository", modelProfileRepository);
        ReflectionTestUtils.setField(port, "skillRepository", skillRepository);
        ReflectionTestUtils.setField(port, "skillContentStore", skillContentStore);
        ReflectionTestUtils.setField(port, "credentialEncryptionUtil", credentialEncryptionUtil);
        ReflectionTestUtils.setField(port, "environmentRepository", environmentRepository);
        ReflectionTestUtils.setField(port, "memoryStoreApi", memoryStoreApi);
        ReflectionTestUtils.setField(port, "secretResolutionPort", secretResolutionPort);
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
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "2");

        // then（发布号十进制解析 → 版本快照 + 模型配置，凭证在基础设施层解密）
        assertEquals("agent-a", resolved.agentId());
        assertEquals(2, resolved.versionNumber());
        assertEquals("v2", resolved.versionName());
        assertEquals("你是数据分析专家", resolved.system());
        assertEquals("openai:gpt-4", resolved.modelIndicator());
        assertEquals(10, resolved.maxIters());
        assertEquals("sk-plain", resolved.credential());
        assertEquals("https://api.example.com/v1", resolved.apiEndpointUrl());
        assertEquals(0, resolved.skills().size());
        assertEquals(0, resolved.dataSourceIds().size());
        assertNull(resolved.environment());
        assertEquals(0, resolved.memoryStores().size());
    }

    @Test
    void should_resolveEnvironmentAndMemory_when_resolve_given_versionWithReferences() {
        // given（版本引用环境与记忆库，装配出版格式化引用，不泄露本 BC 枚举/值对象）
        stubDefinition(false);
        AgentVersion versionRow = AgentVersion.restore(
                1L, "v-id-2", "agent-a", 2, "v2", null, "",
                "p-1", null, null, null, "env-1", "[\"mem-1\"]", null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 2))
                .thenReturn(Optional.of(versionRow));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        when(environmentRepository.findByEnvironmentId("env-1")).thenReturn(Optional.of(environment()));
        when(memoryStoreApi.resolveByIds(List.of("mem-1")))
                .thenReturn(List.of(new MemoryStoreReferenceDTO("mem-1", "会话记忆", "SHORT_TERM", null)));

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "2");

        // then（环境类型/沙箱规格摊平为格式化值，记忆类型以字符串名暴露）
        assertEquals("env-1", resolved.environment().environmentId());
        assertEquals("数据分析沙箱", resolved.environment().name());
        assertEquals("LOCAL", resolved.environment().type());
        assertEquals("python:3.12", resolved.environment().image());
        assertEquals(2048, resolved.environment().memoryMb());
        assertEquals("container", resolved.environment().workspaceMode());
        assertEquals(300, resolved.environment().timeoutSeconds());
        assertEquals(1, resolved.memoryStores().size());
        assertEquals("mem-1", resolved.memoryStores().get(0).memoryStoreId());
        assertEquals("SHORT_TERM", resolved.memoryStores().get(0).type());
    }

    @Test
    void should_resolveSkills_when_resolve_given_versionWithMountedSkills() {
        // given（版本挂载技能引用，解析为技能包原始字节出版）
        stubDefinition(false);
        AgentVersion versionRow = AgentVersion.restore(
                1L, "v-id-1", "agent-a", 1, "v1", null, "",
                "p-1", "[{\"skillId\":\"s-1\",\"version\":3}]", null, null, null, null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionRow));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        SkillResource resource = SkillResource.create("s-1", 3, "code-reviewer", "代码评审",
                SkillType.CUSTOM, SkillStorageType.LOCAL_FILE, "s1-v3.zip",
                "0".repeat(64), 32L, SkillResourceManifest.empty());
        when(skillRepository.findBySkillIdAndVersion("s-1", 3)).thenReturn(Optional.of(resource));
        when(skillContentStore.get("s1-v3.zip")).thenReturn("zip-bytes".getBytes(StandardCharsets.UTF_8));

        // when
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1");

        // then
        assertEquals(1, resolved.skills().size());
        assertEquals("s-1", resolved.skills().get(0).skillId());
        assertEquals(3, resolved.skills().get(0).versionNumber());
        assertEquals("code-reviewer", resolved.skills().get(0).name());
    }

    @Test
    void should_throwNotFound_when_resolve_given_nonDecimalReleaseNumber() {
        // given（发布号解析在仓储查询之前：无需 stub 台账查询）

        // when & then
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolve("agent-a", "v1"));
        assertEquals("发布号格式非法", ex.getMessage());
    }

    @Test
    void should_throwNotFound_when_resolve_given_blankReleaseNumber() {
        // given（空白发布号同上，在仓储查询前即拒绝）

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("agent-a", " "));
    }

    @Test
    void should_throwNotFound_when_resolve_given_zeroReleaseNumber() {
        // given（发布号不能小于 1）

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("agent-a", "0"));
    }

    @Test
    void should_throwNotFound_when_resolve_given_missingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("ghost")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("ghost", "1"));
    }

    @Test
    void should_throwNotFound_when_resolve_given_archivedAgent() {
        // given
        stubDefinition(true);

        // when & then（归档 Agent 拒绝创建新会话，无回退）
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> port.resolve("agent-a", "1"));
        assertEquals("Agent已归档，不可创建新会话", ex.getMessage());
    }

    @Test
    void should_throwNotFound_when_resolve_given_missingVersion() {
        // given
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 9))
                .thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("agent-a", "9"));
    }

    @Test
    void should_throwNotFound_when_resolve_given_missingProfile() {
        // given
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(version(1, "v1", null, "p-missing")));
        when(modelProfileRepository.findByProfileId("p-missing")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.resolve("agent-a", "1"));
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
        ResolvedAgentAssemblyDTO resolved = port.resolve("agent-a", "1");

        // then
        assertEquals("dashscope:qwen-plus", resolved.modelIndicator());
    }

    @Test
    void should_validateWithoutDecrypt_when_assertResolvable_given_validAgentAndVersion() {
        // given（轻量校验链路：发布号/Agent/版本/profile 全部存在）
        stubDefinition(false);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 2))
                .thenReturn(Optional.of(version(2, "v2", null, "p-1")));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));

        // when
        port.assertResolvable("agent-a", "2");

        // then（仅校验，不执行凭证解密）
        verify(credentialEncryptionUtil, never()).decrypt(any());
    }

    @Test
    void should_throwNotFound_when_assertResolvable_given_missingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("ghost")).thenReturn(Optional.empty());

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.assertResolvable("ghost", "1"));
    }

    // ==================== 工具 ====================

    @Test
    void should_returnActiveVersion_when_activeVersionNumber_given_existingAgent() {
        // given（active_version=2）
        stubDefinition(false);

        // when
        String version = port.activeVersionNumber("agent-a");

        // then
        assertEquals("2", version);
    }

    @Test
    void should_throwNotFound_when_activeVersionNumber_given_noActiveVersion() {
        // given（尚未存在激活版本：active_version=0）
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null, false, null, 0, 0, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));

        // when & then
        assertThrows(ResourceNotFoundException.class, () -> port.activeVersionNumber("agent-a"));
    }

    private void stubDefinition(boolean archived) {
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null, archived,
                archived ? OffsetDateTime.now(ZoneId.of("Asia/Shanghai")) : null,
                2, 2, null, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));
    }

    private AgentVersion version(int versionNumber, String name, String system, String profileId) {
        return AgentVersion.restore(
                1L, "v-id-" + versionNumber, "agent-a", versionNumber, name, null,
                system != null ? system : "", profileId, null, null, null, null, null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private ModelProfile profile(ApiFormat apiFormat, String modelName, String encryptedCredential) {
        return ModelProfile.create(
                "p-1", "指令模型", null, apiFormat, "https://api.example.com/v1",
                modelName, encryptedCredential, null, null, null, null, 10,
                ModelType.CHAT, null);
    }

    private Environment environment() {
        return Environment.create("env-1", "数据分析沙箱", EnvironmentType.LOCAL,
                SandboxSpec.create("python:3.12", 2048, 2.0, "container", 300), null);
    }
}