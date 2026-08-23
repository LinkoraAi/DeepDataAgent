package com.linkroa.deepdataagent.agent.application.command;

import com.linkroa.deepdataagent.agent.domain.model.SandboxSpec;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;

/**
 * 更新运行环境命令（全量替换）
 *
 * @param environmentId 运行环境业务 ID
 * @param name          名称
 * @param type          环境类型（本期仅 LOCAL）
 * @param sandboxSpec   沙箱执行规格（值对象）
 */
public record UpdateEnvironmentCommand(
        String environmentId,
        String name,
        EnvironmentType type,
        SandboxSpec sandboxSpec
) {
}