package com.linkroa.deepdataagent.runtime.api.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SchedulerLaunchDTO} 契约不变量单测（触发启动契约：必填项校验 + 透传载荷可空归一）。
 */
class SchedulerLaunchDTOTest {

    private SchedulerLaunchDTO valid() {
        return new SchedulerLaunchDTO("1", "agent-1", "3", null, "输入",
                "manual", "dep-1", "{\"K\":\"v\"}", "[]", List.of("vault-a"),
                "[{\"type\":\"user_message\",\"text\":\"开工\"}]", "{\"m\":\"1\"}");
    }

    @Test
    void should_createDTO_when_construct_given_validFields() {
        // given // when
        SchedulerLaunchDTO dto = valid();

        // then
        assertEquals("1", dto.ownerId());
        assertEquals("agent-1", dto.agentId());
        assertEquals("3", dto.versionNumber());
        assertEquals("manual", dto.triggerType());
        assertEquals("dep-1", dto.triggerId());
        assertEquals("{\"K\":\"v\"}", dto.environmentVariables());
        assertEquals(List.of("vault-a"), dto.vaultIds());
        assertEquals("{\"m\":\"1\"}", dto.metadata());
    }

    @Test
    void should_throwException_when_construct_given_blankOwnerId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SchedulerLaunchDTO(" ", "agent-1", "3", null, null, "manual", "dep-1",
                        null, null, null, null, null));
    }

    @Test
    void should_throwException_when_construct_given_blankAgentId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SchedulerLaunchDTO("1", " ", "3", null, null, "manual", "dep-1",
                        null, null, null, null, null));
    }

    @Test
    void should_throwException_when_construct_given_blankVersion() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SchedulerLaunchDTO("1", "agent-1", "", null, null, "manual", "dep-1",
                        null, null, null, null, null));
    }

    @Test
    void should_throwException_when_construct_given_blankTriggerType() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SchedulerLaunchDTO("1", "agent-1", "3", null, null, null, "dep-1",
                        null, null, null, null, null));
    }

    @Test
    void should_throwException_when_construct_given_blankTriggerId() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new SchedulerLaunchDTO("1", "agent-1", "3", null, null, "webhook", null,
                        null, null, null, null, null));
    }

    @Test
    void should_allowBlankOptionalPayloads_when_construct_given_nullPassthrough() {
        // given // when（input / environmentId / 透传载荷可空：运行时回退默认调度提示与默认环境）
        SchedulerLaunchDTO dto = new SchedulerLaunchDTO("1", "agent-1", "3", null, null,
                "manual", "dep-1", null, null, null, null, null);

        // then（vaultIds null 归一空列表）
        assertNull(dto.input());
        assertNull(dto.environmentId());
        assertNull(dto.resourcesJson());
        assertEquals(List.of(), dto.vaultIds());
    }

    @Test
    void should_copyVaultIdsDefensively_when_construct_given_vaultIds() {
        // given
        List<String> vaultIds = new ArrayList<>(List.of("vault-a"));

        // when
        SchedulerLaunchDTO dto = new SchedulerLaunchDTO("1", "agent-1", "3", null, null,
                "manual", "dep-1", null, null, vaultIds, null, null);
        vaultIds.add("vault-b");

        // then（防御性复制：外部修改不影响契约载体）
        assertEquals(List.of("vault-a"), dto.vaultIds());
    }
}
