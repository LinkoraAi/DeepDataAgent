package com.linkroa.deepdataagent.vault.application.service;

import com.linkroa.deepdataagent.agent.api.AgentReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.vault.application.command.CreateSecretCommand;
import com.linkroa.deepdataagent.vault.domain.model.Secret;
import com.linkroa.deepdataagent.vault.domain.repository.SecretRepository;
import com.linkroa.deepdataagent.vault.infrastructure.util.SecretEncryptionUtil;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecretApplicationServiceTest {

    @Mock private SecretRepository secretRepository;
    @Mock private AgentReferenceApi agentReferenceApi;
    @Mock private SecretEncryptionUtil encryptionUtil;
    @Mock private TransactionTemplate transactionTemplate;

    private SecretApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SecretApplicationService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "secretRepository", secretRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "agentReferenceApi", agentReferenceApi);
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

    @Test
    void should_encryptValue_when_create_given_plainValue() {
        // given
        when(encryptionUtil.encrypt("plain-secret")).thenReturn("encrypted-secret");
        when(secretRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Secret saved = service.create(new CreateSecretCommand("模型密钥", "plain-secret"));

        // then
        assertNotNull(saved.secretId());
        assertEquals("模型密钥", saved.name());
        assertEquals("encrypted-secret", saved.encryptedValue());
        verify(encryptionUtil).encrypt("plain-secret");
    }

    @Test
    void should_throwConflict_when_delete_given_stillReferenced() {
        // given
        Secret secret = Secret.create("s-1", "模型密钥", "encrypted", "default");
        when(secretRepository.findBySecretIdForUpdate("s-1")).thenReturn(Optional.of(secret));
        when(agentReferenceApi.countSecretReferences("s-1")).thenReturn(3L);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.delete("s-1"));
        assertTrue(ex.getMessage().contains("被 3 个模型配置引用"));
        verify(secretRepository, never()).deleteBySecretId(any());
    }

    @Test
    void should_delete_when_delete_given_noReference() {
        // given
        Secret secret = Secret.create("s-1", "模型密钥", "encrypted", "default");
        when(secretRepository.findBySecretIdForUpdate("s-1")).thenReturn(Optional.of(secret));
        when(agentReferenceApi.countSecretReferences("s-1")).thenReturn(0L);

        // when
        service.delete("s-1");

        // then
        verify(secretRepository).deleteBySecretId("s-1");
    }
}