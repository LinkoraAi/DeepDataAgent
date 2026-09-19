package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DeploymentStatus} 枚举单测：小写值域与大小写不敏感解析。
 */
class DeploymentStatusTest {

    @Test
    void should_exposeLowerCaseValues_when_getValue_given_eachStatus() {
        // given // when // then
        assertEquals("active", DeploymentStatus.ACTIVE.getValue());
        assertEquals("paused", DeploymentStatus.PAUSED.getValue());
    }

    @Test
    void should_parseStatus_when_fromValue_given_caseInsensitivePaddedInput() {
        // given // when // then（大小写不敏感 + 首尾空白容忍）
        assertEquals(DeploymentStatus.ACTIVE, DeploymentStatus.fromValue("active"));
        assertEquals(DeploymentStatus.ACTIVE, DeploymentStatus.fromValue(" ACTIVE "));
        assertEquals(DeploymentStatus.PAUSED, DeploymentStatus.fromValue("Paused"));
    }

    @Test
    void should_throwException_when_fromValue_given_unknownOrBlankValue() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> DeploymentStatus.fromValue("archived"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentStatus.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> DeploymentStatus.fromValue(null));
    }
}
