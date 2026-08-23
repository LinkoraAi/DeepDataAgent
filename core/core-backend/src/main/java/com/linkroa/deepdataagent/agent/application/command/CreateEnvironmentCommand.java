package com.linkroa.deepdataagent.agent.application.command;

import com.linkroa.deepdataagent.agent.domain.model.SandboxSpec;
import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;

/**
 * 创建运行环境命令
 *
 * @param name        名称
 * @param type        环境类型（本期仅 LOCAL）
 * @param sandboxSpec 沙箱执行规格（值对象）
 */
public record CreateEnvironmentCommand(
        String name,
        EnvironmentType type,
        SandboxSpec sandboxSpec
) {
}