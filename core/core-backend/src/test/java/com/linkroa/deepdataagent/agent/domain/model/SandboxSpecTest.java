package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxSpecTest {

    @Test
    void should_createSpec_when_create_given_validFields() {
        // given // when
        SandboxSpec spec = SandboxSpec.create("opensandbox/code-interpreter:latest", 512, 2.0, "read-write", 1800);

        // then
        assertEquals("opensandbox/code-interpreter:latest", spec.image());
        assertEquals(512, spec.memoryMb());
        assertEquals(2.0, spec.cpu());
        assertEquals("read-write", spec.workspaceMode());
        assertEquals(1800, spec.timeoutSeconds());
    }

    @Test
    void should_throwException_when_create_given_blankImage() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SandboxSpec.create(" ", 512, 2.0, "read-write", 1800));
    }

    @Test
    void should_throwException_when_create_given_imageExceeds255Chars() {
        // given
        String longImage = "img/".concat("a".repeat(300));

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SandboxSpec.create(longImage, 512, 2.0, "read-write", 1800));
    }

    @Test
    void should_throwException_when_create_given_negativeMemory() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SandboxSpec.create("img:1", -1, 2.0, "read-write", 1800));
    }

    @Test
    void should_throwException_when_create_given_negativeCpu() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SandboxSpec.create("img:1", 512, -0.5, "read-write", 1800));
    }

    @Test
    void should_throwException_when_create_given_blankWorkspaceMode() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SandboxSpec.create("img:1", 512, 2.0, " ", 1800));
    }

    @Test
    void should_throwException_when_create_given_nonPositiveTimeout() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> SandboxSpec.create("img:1", 512, 2.0, "read-write", 0));
    }
}