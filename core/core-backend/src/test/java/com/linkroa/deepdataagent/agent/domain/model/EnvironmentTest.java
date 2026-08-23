package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvironmentTest {

    private static SandboxSpec spec() {
        return SandboxSpec.create("opensandbox/code-interpreter:latest", 512, 2.0, "read-write", 1800);
    }

    @Test
    void should_createEnvironment_when_create_given_validFields() {
        // given // when
        Environment environment = Environment.create("env-1", "沙箱环境", EnvironmentType.LOCAL, spec(), "ws-default");

        // then
        assertEquals("env-1", environment.environmentId());
        assertEquals("沙箱环境", environment.name());
        assertEquals(EnvironmentType.LOCAL, environment.type());
        assertEquals("ws-default", environment.workspaceId());
    }

    @Test
    void should_throwException_when_create_given_blankName() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Environment.create("env-1", " ", EnvironmentType.LOCAL, spec(), "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_nameExceeds64Chars() {
        // given
        String longName = "环".repeat(70);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Environment.create("env-1", longName, EnvironmentType.LOCAL, spec(), "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_invalidNamePattern() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Environment.create("env-1", "1abc!", EnvironmentType.LOCAL, spec(), "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_nullType() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Environment.create("env-1", "沙箱环境", null, spec(), "ws-default"));
    }

    @Test
    void should_throwException_when_create_given_nullSandboxSpec() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> Environment.create("env-1", "沙箱环境", EnvironmentType.LOCAL, null, "ws-default"));
    }
}