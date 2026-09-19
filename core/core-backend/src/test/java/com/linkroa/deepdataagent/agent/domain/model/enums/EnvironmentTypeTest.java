package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EnvironmentType} 值域与解析单测（config 结构化改造后的两值收敛）。
 */
class EnvironmentTypeTest {

    @Test
    void should_returnCanonicalLowercase_when_value_given_eachType() {
        // given // when // then
        assertEquals("cloud", EnvironmentType.CLOUD.value());
        assertEquals("self_hosted", EnvironmentType.SELF_HOSTED.value());
    }

    @Test
    void should_parseCaseInsensitively_when_fromValue_given_validValues() {
        // given // when // then（大小写不敏感 + 首尾空白容错）
        assertEquals(EnvironmentType.CLOUD, EnvironmentType.fromValue("cloud"));
        assertEquals(EnvironmentType.CLOUD, EnvironmentType.fromValue(" CLOUD "));
        assertEquals(EnvironmentType.SELF_HOSTED, EnvironmentType.fromValue("Self_Hosted"));
    }

    @Test
    void should_reject_when_fromValue_given_legacyOrUnknownValues() {
        // given // when // then（旧自建取值与未知值一律拒绝）
        assertThrows(IllegalArgumentException.class, () -> EnvironmentType.fromValue("local"));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentType.fromValue("SANDBOX"));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentType.fromValue("remote"));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentType.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentType.fromValue(null));
    }
}
