package com.linkroa.deepdataagent.agent.domain.service;

import com.linkroa.deepdataagent.agent.domain.model.AgentTool;
import com.linkroa.deepdataagent.agent.domain.model.SkillBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentConfigValidator} 单元测试：tools / skills 数量上限。
 * <p>MCP 服务器声明能力已废止，服务器名唯一性 / 引用完整性校验不再存在。</p>
 */
class AgentConfigValidatorTest {

    /** 钉版版本键（创建时刻 epoch 微秒字符串）。 */
    private static final String EPOCH = "1759178010641129";

    @Test
    void should_pass_when_validate_given_consistentConfiguration() {
        // given
        List<AgentTool> tools = List.of(new AgentTool(AgentTool.TYPE_AGENT_TOOLSET, List.of("Read"),
                List.of(), List.of(), null, null, Map.of()));
        List<SkillBinding> skills = List.of(new SkillBinding(SkillBinding.TYPE_CUSTOM, "skill_1", EPOCH));

        // when & then
        assertDoesNotThrow(() -> AgentConfigValidator.validate(tools, skills));
    }

    @Test
    void should_throw_when_validate_given_moreThanMaxTools() {
        // given
        List<AgentTool> tools = IntStream.rangeClosed(1, AgentTool.MAX_TOOLS + 1)
                .mapToObj(i -> new AgentTool(AgentTool.TYPE_AGENT_TOOLSET, List.of(), List.of(),
                        List.of(), null, null, Map.of()))
                .toList();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigValidator.validate(tools, List.of()));
    }

    @Test
    void should_throw_when_validate_given_moreThanMaxSkills() {
        // given
        List<SkillBinding> skills = IntStream.rangeClosed(1, SkillBinding.MAX_BINDINGS + 1)
                .mapToObj(i -> new SkillBinding(SkillBinding.TYPE_CUSTOM, "skill_" + i, EPOCH))
                .toList();

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfigValidator.validate(List.of(), skills));
    }

    @Test
    void should_pass_when_validate_given_nullLists() {
        // given & when & then
        assertDoesNotThrow(() -> AgentConfigValidator.validate(null, null));
    }
}