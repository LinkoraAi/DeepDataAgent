package com.linkroa.deepdataagent.memory.application.convert;

import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.controller.request.CreateMemoryStoreRequest;
import com.linkroa.deepdataagent.memory.domain.model.enums.MemoryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryStoreCommandConvertTest {

    @Test
    void should_parseLongTerm_when_toCreateCommand_given_validRequest() {
        // given
        CreateMemoryStoreRequest request = new CreateMemoryStoreRequest("长期记忆", "LONG_TERM");

        // when
        CreateMemoryStoreCommand command = MemoryStoreCommandConvert.INSTANCE.toCreateCommand(request);

        // then
        assertEquals("长期记忆", command.name());
        assertEquals(MemoryType.LONG_TERM, command.type());
    }

    @Test
    void should_rejectIllegalType_when_toCreateCommand_given_unknownType() {
        // given
        CreateMemoryStoreRequest request = new CreateMemoryStoreRequest("记忆", "EPISODIC");

        // when // then
        assertThrows(IllegalArgumentException.class, () -> MemoryStoreCommandConvert.INSTANCE.toCreateCommand(request));
    }
}