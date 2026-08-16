package com.linkroa.deepdataagent.agent.application.assembler;

import com.linkroa.deepdataagent.agent.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishSkillVersionCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateSkillRequest;
import com.linkroa.deepdataagent.agent.controller.request.PublishSkillVersionRequest;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import org.springframework.stereotype.Component;

/**
 * 技能请求装配器（Request → Command，内容二进制随 multipart 传入）
 */
@Component
public class SkillCommandAssembler {

    public CreateSkillCommand toCreateCommand(CreateSkillRequest request, byte[] content) {
        return new CreateSkillCommand(
                request.name(),
                request.description(),
                parseSkillType(request.skillType()),
                content,
                request.sha256()
        );
    }

    public PublishSkillVersionCommand toPublishCommand(String skillId, PublishSkillVersionRequest request, byte[] content) {
        return new PublishSkillVersionCommand(skillId, request.description(), content, request.sha256());
    }

    private SkillType parseSkillType(Integer code) {
        if (code == null) {
            return SkillType.CUSTOM;
        }
        return SkillType.fromCode(code);
    }
}