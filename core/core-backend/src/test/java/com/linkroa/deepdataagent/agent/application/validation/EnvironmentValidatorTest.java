package com.linkroa.deepdataagent.agent.application.validation;

import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.SandboxSpec;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentValidatorTest {

    private Environment buildEnvironment() {
        return Environment.create("env-1", "本地环境", EnvironmentType.LOCAL,
                SandboxSpec.create("ubuntu:22.04", 512, 1.0, "read-only", 300), "default");
    }

    @Test
    void should_throwConflict_when_validateDelete_given_referenced() {
        // given // when
        ResourceConflictException ex = assertThrows(ResourceConflictException.class,
                () -> EnvironmentValidator.validateDelete(buildEnvironment(), 2L));

        // then
        assertTrue(ex.getMessage().contains("被 2 个 Agent 版本引用"));
    }

    @Test
    void should_notThrow_when_validateDelete_given_noReference() {
        // given // when // then
        assertDoesNotThrow(() -> EnvironmentValidator.validateDelete(buildEnvironment(), 0L));
    }
}