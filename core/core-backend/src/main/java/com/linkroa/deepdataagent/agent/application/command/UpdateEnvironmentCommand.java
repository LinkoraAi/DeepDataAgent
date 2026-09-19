package com.linkroa.deepdataagent.agent.application.command;

import com.linkroa.deepdataagent.agent.domain.model.EnvironmentConfig;

/**
 * 更新运行环境命令（全量替换）
 *
 * @param environmentId 运行环境业务 ID
 * @param name          名称（仅非空校验）
 * @param description   描述（可空，缺省空串）
 * @param config        环境配置（值对象；null 表示缺省 {@code {"type":"cloud"}}）
 * @param metadata      自定义元数据 JSON 文本（可空，归一为 {@code "{}"}）
 */
public record UpdateEnvironmentCommand(
        String environmentId,
        String name,
        String description,
        EnvironmentConfig config,
        String metadata
) {
}