package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentValidatorTest {

    private Environment buildEnvironment() {
        return Environment.create("env-1", "云端环境", null, EnvironmentConfig.cloudDefault(), null, 1L);
    }

    @Test
    void should_throwConflict_when_validateDelete_given_referenced() {
        // given // when
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> EnvironmentValidator.validateDelete(buildEnvironment(), 2L));

        // then
        assertTrue(ex.getMessage().contains("被 2 个会话引用"));
    }

    @Test
    void should_notThrow_when_validateDelete_given_noReference() {
        // given // when // then
        assertDoesNotThrow(() -> EnvironmentValidator.validateDelete(buildEnvironment(), 0L));
    }

    @Test
    void should_throwConflict_when_validateArchive_given_referenced() {
        // given // when
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> EnvironmentValidator.validateArchive(buildEnvironment(), 2L));

        // then
        assertTrue(ex.getMessage().contains("无法归档"));
    }

    @Test
    void should_notThrow_when_validateArchive_given_noReference() {
        // given // when // then
        assertDoesNotThrow(() -> EnvironmentValidator.validateArchive(buildEnvironment(), 0L));
    }
}
