package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.application.contract.ResolvedAgentAssemblyDTO;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.domain.repository.SkillContentStore;
import com.linkroa.deepdataagent.agent.domain.repository.SkillRepository;
import com.linkroa.deepdataagent.agent.infrastructure.util.ModelCredentialEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    @Test
    void should_resolveSkills_when_resolve_given_versionWithMountedSkills() {
        // given（版本挂载技能引用，解析为技能包原始字节出版）
        stubDefinition(false);
        AgentVersion versionRow = AgentVersion.restore(
                1L, "v-id-1", "agent-a", 1, "v1", null, "",
                "p-1", "[{\"skillId\":\"s-1\",\"version\":3}]", null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentVersionRepository.findByAgentIdAndVersionNumber("agent-a", 1))
                .thenReturn(Optional.of(versionRow));
        when(modelProfileRepository.findByProfileId("p-1"))
                .thenReturn(Optional.of(profile(ApiFormat.OPENAI, "gpt-4", "enc-cred")));
        when(credentialEncryptionUtil.decrypt("enc-cred")).thenReturn("sk-plain");
        SkillResource resource = SkillResource.create("s-1", 3, "code-reviewer", "代码评审",
                SkillType.CUSTOM, SkillStorageType.LOCAL_FILE, "s1-v3.zip",
                "0".repeat(64), 32L);
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

    private void stubDefinition(boolean archived) {
        AgentDefinition definition = AgentDefinition.restore(
                1L, "agent-a", "数据分析员", null, archived,
                archived ? OffsetDateTime.now(ZoneId.of("Asia/Shanghai")) : null,
                2, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
        when(agentDefinitionRepository.findByAgentId("agent-a")).thenReturn(Optional.of(definition));
    }

    private AgentVersion version(int versionNumber, String name, String system, String profileId) {
        return AgentVersion.restore(
                1L, "v-id-" + versionNumber, "agent-a", versionNumber, name, null,
                system != null ? system : "", profileId, null, null, null,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private ModelProfile profile(ApiFormat apiFormat, String modelName, String encryptedCredential) {
        return ModelProfile.create(
                "p-1", "指令模型", null, apiFormat, "https://api.example.com/v1",
                modelName, encryptedCredential, null, null, null, 10,
                ModelType.CHAT, null);
    }
}