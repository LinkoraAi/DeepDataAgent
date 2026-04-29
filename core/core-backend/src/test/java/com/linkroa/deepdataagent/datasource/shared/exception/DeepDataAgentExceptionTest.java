package com.linkroa.deepdataagent.datasource.shared.exception;

import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeepDataAgentExceptionTest {

    @Test
    void should_createException_when_constructor_given_message() {
        DeepDataAgentException exception = new DeepDataAgentException("test error");
        assertEquals("test error", exception.getMessage());
    }

    @Test
    void should_beRuntimeException_when_instanceof_given_exception() {
        DeepDataAgentException exception = new DeepDataAgentException("error");
        assertInstanceOf(RuntimeException.class, exception);
    }
}
