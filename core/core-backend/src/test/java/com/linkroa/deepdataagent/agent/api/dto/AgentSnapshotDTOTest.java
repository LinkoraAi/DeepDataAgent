package com.linkroa.deepdataagent.agent.api.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentSnapshotDTO} 嵌入快照契约边界校验单测。
 */
class AgentSnapshotDTOTest {

    @Test
    void should_acceptFormattedSnapshot_when_construct_given_validAgent() {
        // given & when
        AgentSnapshotDTO dto = new AgentSnapshotDTO("agent-1", 2,
                Map.of("id", "agent-1", "type", "agent", "version", 2, "system", "你是分析助手"));

        // then
        assertEquals("agent-1", dto.agentId());
        assertEquals(2, dto.versionNumber());
        assertEquals("agent", dto.agent().get("type"));
    }

    @Test
    void should_reject_when_construct_given_blankAgentId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSnapshotDTO(" ", 1, Map.of("id", "x")));
    }

    @Test
    void should_reject_when_construct_given_nonPositiveVersion() {
        // given & when & then（发布号必须 ≥1）
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSnapshotDTO("agent-1", 0, Map.of("id", "agent-1")));
    }

    @Test
    void should_reject_when_construct_given_nullSnapshot() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSnapshotDTO("agent-1", 1, null));
    }
}
