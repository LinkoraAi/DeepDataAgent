package com.linkroa.deepdataagent.agent.application.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EnvironmentReferenceDTO} 运行环境引用契约边界校验单测。
 */
class EnvironmentReferenceDTOTest {

    @Test
    void should_acceptFormattedValues_when_construct_given_validEnvironment() {
        // given & when
        EnvironmentReferenceDTO dto = new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "LOCAL", "python:3.12", 2048, 2.0, "container", 300);

        // then
        assertEquals("env-1", dto.environmentId());
        assertEquals("数据分析沙箱", dto.name());
        assertEquals("LOCAL", dto.type());
        assertEquals("python:3.12", dto.image());
        assertEquals(2048, dto.memoryMb());
        assertEquals(2.0, dto.cpu());
        assertEquals("container", dto.workspaceMode());
        assertEquals(300, dto.timeoutSeconds());
    }

    @Test
    void should_reject_when_construct_given_blankEnvironmentId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                " ", "数据分析沙箱", "LOCAL", "python:3.12", 2048, 2.0, "container", 300));
    }

    @Test
    void should_reject_when_construct_given_blankName() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "", "LOCAL", "python:3.12", 2048, 2.0, "container", 300));
    }

    @Test
    void should_reject_when_construct_given_blankType() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "", "python:3.12", 2048, 2.0, "container", 300));
    }

    @Test
    void should_reject_when_construct_given_blankImage() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "LOCAL", " ", 2048, 2.0, "container", 300));
    }

    @Test
    void should_reject_when_construct_given_blankWorkspaceMode() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "LOCAL", "python:3.12", 2048, 2.0, "", 300));
    }

    @Test
    void should_reject_when_construct_given_negativeMemory() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "LOCAL", "python:3.12", -1, 2.0, "container", 300));
    }

    @Test
    void should_reject_when_construct_given_negativeCpu() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "LOCAL", "python:3.12", 2048, -2.0, "container", 300));
    }

    @Test
    void should_reject_when_construct_given_zeroTimeout() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "LOCAL", "python:3.12", 2048, 2.0, "container", 0));
    }
}