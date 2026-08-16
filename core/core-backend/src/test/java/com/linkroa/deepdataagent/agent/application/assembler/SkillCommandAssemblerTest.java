package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishSkillVersionCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateSkillRequest;
import com.linkroa.deepdataagent.agent.controller.request.PublishSkillVersionRequest;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 技能请求装配器测试
 */
class SkillCommandAssemblerTest {

    private final SkillCommandAssembler assembler = new SkillCommandAssembler();

    @Test
    void should_mapSkillFields_when_toCreateCommand_given_typeCodeAndContent() {
        // given
        CreateSkillRequest request = new CreateSkillRequest("评分脚本", "技能描述", 2, "sha-abc");
        byte[] content = new byte[]{1, 2, 3};

        // when
        CreateSkillCommand command = assembler.toCreateCommand(request, content);

        // then
        assertEquals("评分脚本", command.name());
        assertEquals("技能描述", command.description());
        assertEquals(SkillType.OFFICIAL, command.skillType());
        assertArrayEquals(content, command.content());
        assertEquals("sha-abc", command.declaredSha256());
    }

    @Test
    void should_defaultToCustomType_when_toCreateCommand_given_nullSkillType() {
        // given
        CreateSkillRequest request = new CreateSkillRequest("评分脚本", null, null, null);

        // when
        CreateSkillCommand command = assembler.toCreateCommand(request, new byte[]{1});

        // then
        assertEquals(SkillType.CUSTOM, command.skillType());
    }

    @Test
    void should_mapPublishFields_when_toPublishCommand_given_versionRequestAndContent() {
        // given
        PublishSkillVersionRequest request = new PublishSkillVersionRequest("优化后的脚本", "sha-xyz");
        byte[] content = new byte[]{9, 8, 7};

        // when
        PublishSkillVersionCommand command = assembler.toPublishCommand("skill-1", request, content);

        // then
        assertEquals("skill-1", command.skillId());
        assertEquals("优化后的脚本", command.description());
        assertArrayEquals(content, command.content());
        assertEquals("sha-xyz", command.declaredSha256());
    }
}