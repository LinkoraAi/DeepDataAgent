package com.linkroa.deepdataagent.agent.domain.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DeploymentTriggerType} 触发类型值域单测（cron / webhook / manual，源码小写取值，
 * 大小写不敏感解析，值域外拒绝）。
 */
class DeploymentTriggerTypeTest {

    @Test
    void should_returnSourceValue_when_getValue_given_allTypes() {
        // given // when // then
        assertEquals("cron", DeploymentTriggerType.CRON.getValue());
        assertEquals("webhook", DeploymentTriggerType.WEBHOOK.getValue());
        assertEquals("manual", DeploymentTriggerType.MANUAL.getValue());
    }

    @Test
    void should_parseCaseInsensitive_when_fromValue_given_upperOrMixedCase() {
        // given // when // then
        assertEquals(DeploymentTriggerType.WEBHOOK, DeploymentTriggerType.fromValue("WEBHOOK"));
        assertEquals(DeploymentTriggerType.CRON, DeploymentTriggerType.fromValue("Cron"));
        assertEquals(DeploymentTriggerType.MANUAL, DeploymentTriggerType.fromValue(" manual "));
    }

    @Test
    void should_throwException_when_fromValue_given_unknownValue() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> DeploymentTriggerType.fromValue("on_demand"));
    }

    @Test
    void should_throwException_when_fromValue_given_blankValue() {
        // given // when // then
        assertThrows(IllegalArgumentException.class, () -> DeploymentTriggerType.fromValue(" "));
        assertThrows(IllegalArgumentException.class, () -> DeploymentTriggerType.fromValue(null));
    }
}