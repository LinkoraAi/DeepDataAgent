package com.linkroa.deepdataagent.agent.application.contract;

import org.apache.commons.lang3.StringUtils;

/**
 * 运行环境引用契约（发布语言 DTO，Published Language）。
 * <p>由 agent BC 在应用边界出版，作为 {@code ResolvedAgentAssemblyDTO} 的环境引用字段，
 * 供下游 runtime BC 的防腐层消费。对外输出已格式化值（环境类型以字符串名暴露，
 * 沙箱规格摊平为镜像 / 内存 / CPU / 工作目录模式 / 超时），不泄露本 BC 领域枚举
 * {@code EnvironmentType} 与值对象 {@code SandboxSpec}。</p>
 *
 * @param environmentId 运行环境业务 ID
 * @param name          运行环境名称
 * @param type          环境类型（格式化字符串名：LOCAL）
 * @param image         沙箱镜像标识
 * @param memoryMb      沙箱内存大小（MB）
 * @param cpu           沙箱 CPU 核数
 * @param workspaceMode 工作目录模式
 * @param timeoutSeconds 超时时间（秒）
 */
public record EnvironmentReferenceDTO(
        String environmentId,
        String name,
        String type,
        String image,
        int memoryMb,
        double cpu,
        String workspaceMode,
        int timeoutSeconds
) {

    /**
     * 紧凑构造器：契约边界校验
     */
    public EnvironmentReferenceDTO {
        if (StringUtils.isBlank(environmentId)) {
            throw new IllegalArgumentException("运行环境ID不能为空");
        }
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("运行环境名称不能为空");
        }
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("环境类型不能为空");
        }
        if (StringUtils.isBlank(image)) {
            throw new IllegalArgumentException("沙箱镜像不能为空");
        }
        if (StringUtils.isBlank(workspaceMode)) {
            throw new IllegalArgumentException("工作目录模式不能为空");
        }
        if (memoryMb < 0) {
            throw new IllegalArgumentException("内存大小不能为负数");
        }
        if (cpu < 0) {
            throw new IllegalArgumentException("CPU大小不能为负数");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("超时时间必须大于0");
        }
    }
}