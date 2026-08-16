package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateModelProfileCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateModelProfileCommand;
import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ApiFormat;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelType;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.infrastructure.util.ModelCredentialEncryptionUtil;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置应用服务单测（写操作：创建 / 更新凭证语义 / 禁用 / 删除冲突）
 */
@ExtendWith(MockitoExtension.class)
class ModelProfileApplicationServiceTest {

    @Mock private ModelProfileRepository modelProfileRepository;
    @Mock private AgentVersionRepository agentVersionRepository;
    @Mock private ModelCredentialEncryptionUtil encryptionUtil;
    @Mock private TransactionTemplate transactionTemplate;

    private ModelProfileApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ModelProfileApplicationService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelProfileRepository", modelProfileRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "agentVersionRepository", agentVersionRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "encryptionUtil", encryptionUtil);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private ModelProfile createEnabledProfile(String profileId, String name) {
        return ModelProfile.restore(
                profileId, name, null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                "encrypted", "gpt", 8192, 2048, 10, ModelType.CHAT, null,
                ModelProfileStatus.ENABLED,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    private CreateModelProfileCommand buildCreateCommand(String name) {
        return new CreateModelProfileCommand(name, null, ApiFormat.OPENAI, "https://example.com/v1",
                "gpt-4", "sk-plain", "gpt", 8192, 2048, 10, ModelType.CHAT, null);
    }

    @Test
    void should_encryptCredential_when_createProfile_given_validCommand() {
        // given
        when(modelProfileRepository.findByDisplayName("chat-profile")).thenReturn(Optional.empty());
        when(encryptionUtil.encrypt("sk-plain")).thenReturn("encrypted-sk");
        when(modelProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        ModelProfile saved = service.createProfile(buildCreateCommand("chat-profile"));

        // then
        assertEquals("encrypted-sk", saved.encryptedCredential());
        verify(encryptionUtil).encrypt("sk-plain");
    }

    @Test
    void should_throwConflict_when_createProfile_given_duplicateName() {
        // given
        when(modelProfileRepository.findByDisplayName("chat-profile"))
                .thenReturn(Optional.of(createEnabledProfile("p-existing", "chat-profile")));

        // when / then
        assertThrows(ResourceConflictException.class,
                () -> service.createProfile(buildCreateCommand("chat-profile")));
        verify(modelProfileRepository, never()).save(any());
    }

    @Test
    void should_keepOriginalCredential_when_updateProfile_given_nullCredential() {
        // given
        ModelProfile existing = createEnabledProfile("p1", "chat-profile");
        when(modelProfileRepository.findByProfileId("p1")).thenReturn(Optional.of(existing));
        when(modelProfileRepository.findByDisplayName("chat-profile")).thenReturn(Optional.of(existing));
        when(modelProfileRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateModelProfileCommand command = new UpdateModelProfileCommand(
                "p1", "chat-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                null, "gpt", 8192, 2048, 10, ModelType.CHAT, null);

        // when
        ModelProfile updated = service.updateProfile(command);

        // then
        assertEquals("encrypted", updated.encryptedCredential());
        verify(encryptionUtil, never()).encrypt(anyString());
    }

    @Test
    void should_clearCredential_when_updateProfile_given_emptyStringCredential() {
        // given
        ModelProfile existing = createEnabledProfile("p1", "chat-profile");
        when(modelProfileRepository.findByProfileId("p1")).thenReturn(Optional.of(existing));
        when(modelProfileRepository.findByDisplayName("chat-profile")).thenReturn(Optional.of(existing));
        when(modelProfileRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateModelProfileCommand command = new UpdateModelProfileCommand(
                "p1", "chat-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                "", "gpt", 8192, 2048, 10, ModelType.CHAT, null);

        // when
        ModelProfile updated = service.updateProfile(command);

        // then
        assertEquals("", updated.encryptedCredential());
    }

    @Test
    void should_reencryptCredential_when_updateProfile_given_newCredential() {
        // given
        ModelProfile existing = createEnabledProfile("p1", "chat-profile");
        when(modelProfileRepository.findByProfileId("p1")).thenReturn(Optional.of(existing));
        when(modelProfileRepository.findByDisplayName("chat-profile")).thenReturn(Optional.of(existing));
        when(encryptionUtil.encrypt("sk-new")).thenReturn("encrypted-new");
        when(modelProfileRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateModelProfileCommand command = new UpdateModelProfileCommand(
                "p1", "chat-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                "sk-new", "gpt", 8192, 2048, 10, ModelType.CHAT, null);

        // when
        ModelProfile updated = service.updateProfile(command);

        // then
        assertEquals("encrypted-new", updated.encryptedCredential());
    }

    @Test
    void should_throwConflict_when_updateProfile_given_duplicateNameExcludingSelf() {
        // given
        ModelProfile existing = createEnabledProfile("p1", "chat-profile");
        when(modelProfileRepository.findByProfileId("p1")).thenReturn(Optional.of(existing));
        when(modelProfileRepository.findByDisplayName("other-profile"))
                .thenReturn(Optional.of(createEnabledProfile("p2", "other-profile")));

        UpdateModelProfileCommand command = new UpdateModelProfileCommand(
                "p1", "other-profile", null, ApiFormat.OPENAI, "https://example.com/v1", "gpt-4",
                null, "gpt", 8192, 2048, 10, ModelType.CHAT, null);

        // when / then
        assertThrows(ResourceConflictException.class, () -> service.updateProfile(command));
    }

    @Test
    void should_throwConflict_when_deleteProfile_given_stillReferenced() {
        // given
        ModelProfile existing = createEnabledProfile("p1", "chat-profile");
        when(modelProfileRepository.findByProfileIdForUpdate("p1")).thenReturn(Optional.of(existing));
        when(agentVersionRepository.countByModelProfileId("p1")).thenReturn(2L);

        // when / then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.deleteProfile("p1"));
        assertTrue(ex.getMessage().contains("被 2 个 Agent 版本引用"));
        verify(modelProfileRepository, never()).deleteByProfileId(anyString());
    }

    @Test
    void should_deleteProfile_when_deleteProfile_given_noReference() {
        // given
        ModelProfile existing = createEnabledProfile("p1", "chat-profile");
        when(modelProfileRepository.findByProfileIdForUpdate("p1")).thenReturn(Optional.of(existing));
        when(agentVersionRepository.countByModelProfileId("p1")).thenReturn(0L);

        // when
        service.deleteProfile("p1");

        // then
        verify(modelProfileRepository).findByProfileIdForUpdate("p1");
        verify(modelProfileRepository).deleteByProfileId("p1");
    }

    @Test
    void should_throwNotFound_when_disableProfile_given_notExist() {
        // given
        when(modelProfileRepository.findByProfileId("missing")).thenReturn(Optional.empty());

        // when / then
        assertThrows(ResourceNotFoundException.class, () -> service.disableProfile("missing"));
    }
}