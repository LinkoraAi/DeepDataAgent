package com.linkroa.deepdataagent.memory.application.validation;

import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryType;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreValidatorTest {

    private MemoryStore buildStore() {
        return MemoryStore.create("m-1", "长期记忆", MemoryType.LONG_TERM, "default");
    }

    @Test
    void should_throwConflict_when_validateDelete_given_referenced() {
        // given // when
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> MemoryStoreValidator.validateDelete(buildStore(), 1L));

        // then
        assertTrue(ex.getMessage().contains("被 1 个 Agent 版本引用"));
    }

    @Test
    void should_notThrow_when_validateDelete_given_noReference() {
        // given // when // then
        assertDoesNotThrow(() -> MemoryStoreValidator.validateDelete(buildStore(), 0L));
    }
}