package com.linkroa.deepdataagent.agent.application.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentToolVisibilityDTO} 契约边界单测：null 归一、元素空白拒绝、名单不可变、约束判定。
 */
class AgentToolVisibilityDTOTest {

    @Test
    void should_normalizeNullListsToEmpty_when_construct_given_nullArguments() {
        // given & when（契约允许缺省名单，null 归一为空 = 无约束）
        AgentToolVisibilityDTO dto = new AgentToolVisibilityDTO(null, null);

        // then
        assertTrue(dto.allowedTools().isEmpty());
        assertTrue(dto.hiddenTools().isEmpty());
        assertFalse(dto.hasConstraint());
    }

    @Test
    void should_copyToImmutableList_when_construct_given_populatedNames() {
        // given（外部可变列表）
        List<String> allowed = new ArrayList<>(List.of("Bash", "Read"));

        // when
        AgentToolVisibilityDTO dto = new AgentToolVisibilityDTO(allowed, List.of("Write"));
        allowed.add("Edit");

        // then（拷贝快照，不随外部修改；名单本身不可变）
        assertEquals(List.of("Bash", "Read"), dto.allowedTools());
        assertEquals(List.of("Write"), dto.hiddenTools());
        assertThrows(UnsupportedOperationException.class, () -> dto.allowedTools().add("Grep"));
    }

    @Test
    void should_throwIllegalArgument_when_construct_given_blankElement() {
        // given & when & then（空白元素视为契约违例）
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolVisibilityDTO(List.of(" "), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolVisibilityDTO(List.of(), List.of("")));
    }

    @Test
    void should_reportConstraint_when_hasConstraint_given_eitherListPopulated() {
        // given
        AgentToolVisibilityDTO none = new AgentToolVisibilityDTO(List.of(), List.of());
        AgentToolVisibilityDTO withAllowed = new AgentToolVisibilityDTO(List.of("Bash"), List.of());
        AgentToolVisibilityDTO withHidden = new AgentToolVisibilityDTO(List.of(), List.of("Grep"));

        // when & then
        assertFalse(none.hasConstraint());
        assertTrue(withAllowed.hasConstraint());
        assertTrue(withHidden.hasConstraint());
    }
}
