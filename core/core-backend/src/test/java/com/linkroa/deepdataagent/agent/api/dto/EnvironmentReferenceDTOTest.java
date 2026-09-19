package com.linkroa.deepdataagent.agent.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EnvironmentReferenceDTO} 运行环境引用契约边界校验单测。
 */
class EnvironmentReferenceDTOTest {

    @Test
    void should_acceptFormattedValues_when_construct_given_validEnvironment() {
        // given & when
        EnvironmentReferenceDTO dto = new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", "cloud", "pip install -r requirements.txt");

        // then
        assertEquals("env-1", dto.environmentId());
        assertEquals("数据分析沙箱", dto.name());
        assertEquals("cloud", dto.type());
        assertEquals("pip install -r requirements.txt", dto.setupScript());
    }

    @Test
    void should_acceptNullScript_when_construct_given_noSetupScript() {
        // given & when
        EnvironmentReferenceDTO dto = new EnvironmentReferenceDTO("env-1", "云端环境", "self_hosted", null);

        // then
        assertEquals("self_hosted", dto.type());
        assertNull(dto.setupScript());
    }

    @Test
    void should_reject_when_construct_given_blankEnvironmentId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                " ", "数据分析沙箱", "cloud", null));
    }

    @Test
    void should_reject_when_construct_given_blankName() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "", "cloud", null));
    }

    @Test
    void should_reject_when_construct_given_blankType() {
        // given & when & then
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentReferenceDTO(
                "env-1", "数据分析沙箱", " ", null));
    }
}
