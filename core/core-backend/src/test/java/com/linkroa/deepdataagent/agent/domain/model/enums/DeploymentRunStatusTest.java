package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DeploymentRunStatus} 枚举单测：小写值域与大小写不敏感解析。
 */
class DeploymentRunStatusTest {

    @Test
    void should_exposeLowerCaseValues_when_getValue_given_eachStatus() {
        // given // when // then
        assertEquals("running", DeploymentRunStatus.RUNNING.getValue());
        assertEquals("succeeded", DeploymentRunStatus.SUCCEEDED.getValue());
        assertEquals("failed", DeploymentRunStatus.FAILED.getValue());
        assertEquals("terminated", DeploymentRunStatus.TERMINATED.getValue());
    }

    @Test
    void should_parseStatus_when_fromValue_given_caseInsensitivePaddedInput() {
        // given // when // then（大小写不敏感 + 首尾空白容忍）
        assertEquals(DeploymentRunStatus.RUNNING, DeploymentRunStatus.fromValue("running"));
        assertEquals(DeploymentRunStatus.SUCCEEDED, DeploymentRunStatus.fromValue(" Succeeded "));
        assertEquals(DeploymentRunStatus.TERMINATED, DeploymentRunStatus.fromValue("TERMINATED"));
    }

    @Test
    void should_throwException_when_fromValue_given_unknownOrBlankValue() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> DeploymentRunStatus.fromValue("cancelled"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRunStatus.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> DeploymentRunStatus.fromValue(null));
    }
}
