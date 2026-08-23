package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.SandboxSpec;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.agent.domain.repository.AgentVersionRepository;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnvironmentApplicationServiceTest {

    @Mock private EnvironmentRepository environmentRepository;
    @Mock private AgentVersionRepository agentVersionRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private EnvironmentApplicationService service;

    @BeforeEach
    void setUp() {
        service = new EnvironmentApplicationService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "environmentRepository", environmentRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "agentVersionRepository", agentVersionRepository);
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

    private Environment buildEnvironment(String environmentId, String name) {
        return Environment.restore(null, environmentId, name, EnvironmentType.LOCAL,
                SandboxSpec.create("ubuntu:22.04", 512, 1.0, "read-only", 300),
                "default", null, null, null, null);
    }

    private CreateEnvironmentCommand buildCreateCommand(String name) {
        return new CreateEnvironmentCommand(name, EnvironmentType.LOCAL,
                SandboxSpec.create("ubuntu:22.04", 512, 1.0, "read-only", 300));
    }

    @Test
    void should_generateEnvironmentId_when_create_given_validCommand() {
        // given
        when(environmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Environment saved = service.create(buildCreateCommand("本地环境"));

        // then
        assertNotNull(saved.environmentId());
        assertEquals("本地环境", saved.name());
        assertEquals(EnvironmentType.LOCAL, saved.type());
    }

    @Test
    void should_throwNotFound_when_get_given_notExist() {
        // given
        when(environmentRepository.findByEnvironmentId("missing")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.get("missing"));
    }

    @Test
    void should_throwConflict_when_delete_given_stillReferenced() {
        // given
        when(environmentRepository.findByEnvironmentIdForUpdate("env-1"))
                .thenReturn(Optional.of(buildEnvironment("env-1", "本地环境")));
        when(agentVersionRepository.countByEnvironmentId("env-1")).thenReturn(2L);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.delete("env-1"));
        assertTrue(ex.getMessage().contains("被 2 个 Agent 版本引用"));
        verify(environmentRepository, never()).deleteByEnvironmentId(anyString());
    }

    @Test
    void should_delete_when_delete_given_noReference() {
        // given
        when(environmentRepository.findByEnvironmentIdForUpdate("env-1"))
                .thenReturn(Optional.of(buildEnvironment("env-1", "本地环境")));
        when(agentVersionRepository.countByEnvironmentId("env-1")).thenReturn(0L);

        // when
        service.delete("env-1");

        // then
        verify(environmentRepository).deleteByEnvironmentId("env-1");
    }
}