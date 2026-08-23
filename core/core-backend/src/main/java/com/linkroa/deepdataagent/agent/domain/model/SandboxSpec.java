package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 运行环境沙箱执行规格（值对象）。
 *
 * <p>封装 Environment 的半结构化沙箱规格（镜像 / 内存 / CPU / 工作目录模式 / 超时），
 * 以强类型替代裸 {@code String}/{@code Map}；紧凑构造器内做取值范围校验。</p>
 *
 * @param image          镜像标识
 * @param memoryMb       内存大小（MB）
 * @param cpu            CPU 核数
 * @param workspaceMode  工作目录模式
 * @param timeoutSeconds 超时时间（秒）
 */
public record SandboxSpec(
        String image,
        int memoryMb,
        double cpu,
        String workspaceMode,
        int timeoutSeconds
) {

    /**
     * 紧凑构造器：不变量校验
     */
    public SandboxSpec {
        if (StringUtils.isBlank(image)) {
            throw new IllegalArgumentException("沙箱镜像不能为空");
        }
        if (image.length() > 255) {
            throw new IllegalArgumentException("沙箱镜像标识长度不能超过255个字符");
        }
        if (memoryMb < 0) {
            throw new IllegalArgumentException("内存大小不能为负数");
        }
        if (cpu < 0) {
            throw new IllegalArgumentException("CPU大小不能为负数");
        }
        if (StringUtils.isBlank(workspaceMode)) {
            throw new IllegalArgumentException("工作目录模式不能为空");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("超时时间必须大于0");
        }
    }

    /**
     * 创建沙箱规格
     */
    public static SandboxSpec create(String image, int memoryMb, double cpu, String workspaceMode, int timeoutSeconds) {
        return new SandboxSpec(image, memoryMb, cpu, workspaceMode, timeoutSeconds);
    }
}