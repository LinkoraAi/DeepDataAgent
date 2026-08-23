package com.linkroa.deepdataagent.agent.application.convert;

import com.linkroa.deepdataagent.agent.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishSkillVersionCommand;
import com.linkroa.deepdataagent.agent.controller.request.CreateSkillRequest;
import com.linkroa.deepdataagent.agent.controller.request.PublishSkillVersionRequest;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * 技能请求转换器（Request → Command，内容二进制随 multipart 传入）。
 */
@Mapper
public interface SkillCommandConvert {

    SkillCommandConvert INSTANCE = Mappers.getMapper(SkillCommandConvert.class);

    default CreateSkillCommand toCreateCommand(CreateSkillRequest request, byte[] content) {
        return new CreateSkillCommand(
                request.name(),
                request.description(),
                parseSkillType(request.skillType()),
                content,
                request.sha256()
        );
    }

    default PublishSkillVersionCommand toPublishCommand(String skillId, PublishSkillVersionRequest request, byte[] content) {
        return new PublishSkillVersionCommand(skillId, request.description(), content, request.sha256());
    }

    private static SkillType parseSkillType(Integer code) {
        if (code == null) {
            return SkillType.CUSTOM;
        }
        return SkillType.fromCode(code);
    }
}