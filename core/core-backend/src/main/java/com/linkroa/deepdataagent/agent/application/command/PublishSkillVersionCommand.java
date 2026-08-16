package com.linkroa.deepdataagent.agent.application.command;

/**
 * 发布技能新版本命令（再次上传，版本号 MAX+1）
 *
 * @param skillId         技能业务ID
 * @param description     版本描述（可覆盖）
 * @param content         技能包二进制内容
 * @param declaredSha256  客户端声明的 SHA-256 校验值（可选；提供服务端校验）
 */
public record PublishSkillVersionCommand(
        String skillId,
        String description,
        byte[] content,
        String declaredSha256
) {
}