package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateAgentCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishAgentVersionCommand;
import com.linkroa.deepdataagent.agent.domain.model.AgentDefinition;
import com.linkroa.deepdataagent.agent.domain.model.AgentVersion;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.agent.domain.repository.AgentDefinitionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.domain.service.AgentVersionDomainService;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 创建 / 发布 / 归档 / 删除应用服务单测（含并发发布串行化语义验证）
 */
@ExtendWith(MockitoExtension.class)
class AgentApplicationServiceTest {

    @Mock private AgentDefinitionRepository agentDefinitionRepository;
    @Mock private AgentVersionRepository agentVersionRepository;
    @Mock private ModelProfileRepository modelProfileRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private AgentApplicationService service;

    @BeforeEach
    void setUp() {
        AgentVersionDomainService versionDomainService = new AgentVersionDomainService();
        service = new AgentApplicationService();
        ReflectionTestUtils.setField(service, "agentDefinitionRepository", agentDefinitionRepository);
        ReflectionTestUtils.setField(service, "agentVersionRepository", agentVersionRepository);
        ReflectionTestUtils.setField(service, "modelProfileRepository", modelProfileRepository);
        ReflectionTestUtils.setField(service, "versionDomainService", versionDomainService);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private ModelProfile buildEnabledProfile(String profileId) {
        return ModelProfile.restore(
                profileId, "chat-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                "encrypted", "gpt", 8192, 2048, 10, ModelType.CHAT, null,
                ModelProfileStatus.ENABLED,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private ModelProfile buildDisabledProfile(String profileId) {
        return ModelProfile.restore(
                profileId, "disabled-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                "encrypted", "gpt", 8192, 2048, 10, ModelType.CHAT, null,
                ModelProfileStatus.DISABLED,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private AgentDefinition buildDefinition(String agentId, String name, int latestVersion, boolean archived) {
        return AgentDefinition.restore(
                1L, agentId, name, null, archived, null, latestVersion,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private CreateAgentCommand buildCreateCommand(String name) {
        return new CreateAgentCommand(name, null, "你是助手", "profile-1", null, null, null, null);
    }

    @Test
    void should_createDefinitionWithV1_when_createAgent_given_validCommand() {
        // given
        when(agentDefinitionRepository.findByName("销售助手")).thenReturn(Optional.empty());
        when(modelProfileRepository.findByProfileId("profile-1")).thenReturn(Optional.of(buildEnabledProfile("profile-1")));
        when(agentDefinitionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentVersionRepository.findMaxVersionNumber(anyString())).thenReturn(0);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        AgentDefinition created = service.createAgent(buildCreateCommand("销售助手"));

        // then
        assertNotNull(created.agentId());
        assertEquals("销售助手", created.name());
        assertEquals(1, created.latestVersion());
        verify(agentVersionRepository).save(any());
    }

    @Test
    void should_throwConflict_when_createAgent_given_duplicateName() {
        // given
        when(agentDefinitionRepository.findByName("销售助手"))
                .thenReturn(Optional.of(buildDefinition("agent-existing", "销售助手", 1, false)));

        // when / then
        assertThrows(ResourceConflictException.class, () -> service.createAgent(buildCreateCommand("销售助手")));
        verify(agentDefinitionRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_createAgent_given_missingModelProfile() {
        // given
        when(agentDefinitionRepository.findByName("销售助手")).thenReturn(Optional.empty());
        when(modelProfileRepository.findByProfileId("profile-1")).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.createAgent(buildCreateCommand("销售助手")));
        verify(agentDefinitionRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_createAgent_given_disabledModelProfile() {
        // given
        when(agentDefinitionRepository.findByName("销售助手")).thenReturn(Optional.empty());
        when(modelProfileRepository.findByProfileId("profile-1")).thenReturn(Optional.of(buildDisabledProfile("profile-1")));

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.createAgent(buildCreateCommand("销售助手")));
        verify(agentDefinitionRepository, never()).save(any());
    }

    @Test
    void should_publishNewVersionWithIncrementedNumber_when_publishVersion_given_existingVersions() {
        // given
        AgentDefinition definition = buildDefinition("agent-1", "销售助手", 2, false);
        when(modelProfileRepository.findByProfileId("profile-1")).thenReturn(Optional.of(buildEnabledProfile("profile-1")));
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(definition));
        when(agentVersionRepository.findMaxVersionNumber("agent-1")).thenReturn(2);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v3", null, "新版系统提示", "profile-1", null, null, null, null);

        // when
        AgentVersion published = service.publishVersion(command);

        // then
        assertEquals(3, published.versionNumber());
        assertEquals("新版系统提示", published.system());
        verify(agentDefinitionRepository).update(any());
    }

    @Test
    void should_throwConflict_when_publishVersion_given_archivedAgent() {
        // given
        AgentDefinition archived = buildDefinition("agent-1", "销售助手", 1, true);
        when(modelProfileRepository.findByProfileId("profile-1")).thenReturn(Optional.of(buildEnabledProfile("profile-1")));
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(archived));

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", "profile-1", null, null, null, null);

        // when / then
        assertThrows(ResourceConflictException.class, () -> service.publishVersion(command));
        verify(agentVersionRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_publishVersion_given_missingAgent() {
        // given
        when(modelProfileRepository.findByProfileId("profile-1")).thenReturn(Optional.of(buildEnabledProfile("profile-1")));
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.empty());

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v2", null, "system", "profile-1", null, null, null, null);

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.publishVersion(command));
    }

    @Test
    void should_throwNotFound_when_getAgent_given_missingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-missing")).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.getAgent("agent-missing"));
    }

    @Test
    void should_archiveAgent_when_archiveAgent_given_existingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 1, false)));

        // when
        service.archiveAgent("agent-1");

        // then
        verify(agentDefinitionRepository).updateArchived("agent-1", true);
    }

    @Test
    void should_deleteAgentAndVersions_when_deleteAgent_given_existingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-1"))
                .thenReturn(Optional.of(buildDefinition("agent-1", "销售助手", 1, false)));

        // when
        service.deleteAgent("agent-1");

        // then
        verify(agentDefinitionRepository).deleteByAgentId("agent-1");
        verify(agentVersionRepository).deleteByAgentId("agent-1");
    }

    @Test
    void should_throwNotFound_when_deleteAgent_given_missingAgent() {
        // given
        when(agentDefinitionRepository.findByAgentId("agent-missing")).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.deleteAgent("agent-missing"));
    }

    @Test
    void should_generateDistinctVersionNumbers_when_publishVersion_given_concurrentPublish() {
        // given
        // 模拟两个并发发布：第二个发布时 MAX 为 1（第一个已落库），会同源递增出互不相同的版本号
        AgentDefinition definition = buildDefinition("agent-1", "销售助手", 1, false);
        when(modelProfileRepository.findByProfileId("profile-1")).thenReturn(Optional.of(buildEnabledProfile("profile-1")));
        when(agentDefinitionRepository.findByAgentIdForUpdate("agent-1")).thenReturn(Optional.of(definition));
        when(agentVersionRepository.findMaxVersionNumber("agent-1"))
                .thenReturn(0)
                .thenReturn(1);
        when(agentVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentDefinitionRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PublishAgentVersionCommand command = new PublishAgentVersionCommand(
                "agent-1", "v", null, "system", "profile-1", null, null, null, null);

        // when
        AgentVersion first = service.publishVersion(command);
        AgentVersion second = service.publishVersion(command);

        // then
        assertEquals(1, first.versionNumber());
        assertEquals(2, second.versionNumber());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.versionNumber(), second.versionNumber());
    }
}