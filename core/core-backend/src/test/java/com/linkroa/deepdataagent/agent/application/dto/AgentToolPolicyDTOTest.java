package com.linkroa.deepdataagent.agent.application.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentToolPolicyDTO} 工具权限策略契约边界校验单测。
 * <p>覆盖工具名非空不变量与 permission_policy 三值词汇约束。</p>
 */
class AgentToolPolicyDTOTest {

    @Test
    void should_acceptValidPolicy_when_construct_given_knownVocabulary() {
        // given & when
        AgentToolPolicyDTO allow = new AgentToolPolicyDTO("Read", AgentToolPolicyDTO.POLICY_ALWAYS_ALLOW);
        AgentToolPolicyDTO ask = new AgentToolPolicyDTO("Bash", AgentToolPolicyDTO.POLICY_ALWAYS_ASK);
        AgentToolPolicyDTO deny = new AgentToolPolicyDTO("WebFetch", AgentToolPolicyDTO.POLICY_ALWAYS_DENY);
        AgentToolPolicyDTO nullable = new AgentToolPolicyDTO("Write", null);

        // then
        assertEquals("Read", allow.name());
        assertEquals("always_allow", allow.permissionPolicy());
        assertEquals("always_ask", ask.permissionPolicy());
        assertEquals("always_deny", deny.permissionPolicy());
        // 策略可空 = 平台默认（契约允许，装配侧仅收集非空项）
        assertNull(nullable.permissionPolicy());
    }

    @Test
    void should_reject_when_construct_given_blankName() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolPolicyDTO(" ", AgentToolPolicyDTO.POLICY_ALWAYS_ASK));
    }

    @Test
    void should_reject_when_construct_given_unknownPolicy() {
        // given & when & then（词汇须与 AgentTool.ToolConfig 三值对齐，大小写敏感）
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolPolicyDTO("Bash", "always_ASK"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolPolicyDTO("Bash", "ask_me"));
    }
}
