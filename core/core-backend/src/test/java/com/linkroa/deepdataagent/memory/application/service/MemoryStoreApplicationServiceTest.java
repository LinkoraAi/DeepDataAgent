package com.linkroa.deepdataagent.memory.application.service;

import com.linkroa.deepdataagent.agent.api.AgentReferenceApi;
import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryType;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryStoreRepository;
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
class MemoryStoreApplicationServiceTest {

    @Mock private MemoryStoreRepository memoryStoreRepository;
    @Mock private AgentReferenceApi agentReferenceApi;
    @Mock private TransactionTemplate transactionTemplate;

    private MemoryStoreApplicationService service;

    @BeforeEach
    void setUp() {
        service = new MemoryStoreApplicationService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "memoryStoreRepository", memoryStoreRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "agentReferenceApi", agentReferenceApi);
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

    private MemoryStore buildStore(String memoryId, String name) {
        return MemoryStore.restore(null, memoryId, name, MemoryType.LONG_TERM, "default",
                null, null, null, null);
    }

    @Test
    void should_generateMemoryId_when_create_given_validCommand() {
        // given
        when(memoryStoreRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        MemoryStore saved = service.create(new CreateMemoryStoreCommand("长期记忆", MemoryType.LONG_TERM));

        // then
        assertNotNull(saved.memoryId());
        assertEquals("长期记忆", saved.name());
        assertEquals(MemoryType.LONG_TERM, saved.type());
    }

    @Test
    void should_throwNotFound_when_get_given_notExist() {
        // given
        when(memoryStoreRepository.findByMemoryId("missing")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.get("missing"));
    }

    @Test
    void should_throwConflict_when_delete_given_stillReferenced() {
        // given
        when(memoryStoreRepository.findByMemoryIdForUpdate("m-1"))
                .thenReturn(Optional.of(buildStore("m-1", "长期记忆")));
        when(agentReferenceApi.countMemoryStoreReferences("m-1")).thenReturn(1L);

        // when // then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> service.delete("m-1"));
        assertTrue(ex.getMessage().contains("被 1 个 Agent 版本引用"));
        verify(memoryStoreRepository, never()).deleteByMemoryId(anyString());
    }

    @Test
    void should_delete_when_delete_given_noReference() {
        // given
        when(memoryStoreRepository.findByMemoryIdForUpdate("m-1"))
                .thenReturn(Optional.of(buildStore("m-1", "长期记忆")));
        when(agentReferenceApi.countMemoryStoreReferences("m-1")).thenReturn(0L);

        // when
        service.delete("m-1");

        // then
        verify(memoryStoreRepository).deleteByMemoryId("m-1");
    }
}