package com.linkroa.deepdataagent.agent.controller.response;

/**
 * 环境配置响应（{@code config}：type / packages / setup_script）。
 *
 * @param type         环境配置类型（小写规范值：cloud / self_hosted）
 * @param packages     预装依赖（六类全量回显，无数据为空数组）
 * @param setup_script 准备阶段 shell 脚本（可空；下划线键按契约约定）
 */
public record EnvironmentConfigResponse(
        String type,
        EnvironmentPackagesResponse packages,
        String setup_script
) {
}
