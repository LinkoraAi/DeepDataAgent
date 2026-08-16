package com.linkroa.deepdataagent.agent.application.command;

import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;

/**
 * 创建技能命令（首次上传，生成 v1）
 *
 * @param name            技能名称
 * @param description     技能描述
 * @param skillType       技能类型
 * @param content         技能包二进制内容
 * @param declaredSha256  客户端声明的 SHA-256 校验值（可选；提供服务端校验）
 */
public record CreateSkillCommand(
        String name,
        String description,
        SkillType skillType,
        byte[] content,
        String declaredSha256
) {
}