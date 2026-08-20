package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuntimeSkillRepository} 运行时技能仓储单测：由物化技能构建 AgentSkill。
 */
class RuntimeSkillRepositoryTest {

    @Test
    void should_serveSkill_when_getSkill_given_materializedSkills() {
        // given
        Skill skill = new Skill("code-reviewer", "代码评审", "指令正文", Map.of());
        RuntimeSkillRepository repository = new RuntimeSkillRepository(List.of(skill));

        // when
        AgentSkill agentSkill = repository.getSkill("code-reviewer");

        // then（挂载可调用、只读、未挂载不可见）
        assertEquals("code-reviewer", agentSkill.getName());
        assertEquals("代码评审", agentSkill.getDescription());
        assertEquals("指令正文", agentSkill.getSkillContent());
        assertTrue(repository.skillExists("code-reviewer"));
        assertEquals(List.of("code-reviewer"), repository.getAllSkillNames());
        assertNull(repository.getSkill("ghost"));
        assertFalse(repository.isWriteable());
        assertFalse(repository.delete("code-reviewer"));
    }
}