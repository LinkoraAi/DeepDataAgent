package com.linkroa.deepdataagent.agent.domain.model;

import com.linkroa.deepdataagent.agent.domain.model.enums.EnvironmentType;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 运行环境配置（值对象，Environment {@code config} 契约）。
 * <p>结构：{@code type}（{@code cloud} / {@code self_hosted}）+ {@code packages}
 * （六类包管理器预装依赖，见 {@link EnvironmentPackages}）+ {@code setup_script}
 * （sandbox 准备阶段执行的 shell 脚本，{@code /bin/bash -lc} 解释器，最大 64 KB、
 * 超时 10 分钟，失败导致 Session 启动失败并携带 exit code 与 stderr 摘要）。</p>
 * <p>不变量（紧凑构造器内校验）：{@code type} 必填；{@code self_hosted} 仅支持
 * type 与可选 setup_script（携带 packages 即拒绝）；{@code setupScript} 空白归一为
 * null 且 UTF-8 字节数不超过 64 KB。</p>
 *
 * @param type        环境配置类型（云端托管 / 自托管）
 * @param packages    预装依赖（null 归一为六类全空）
 * @param setupScript 准备阶段 shell 脚本（可空，≤64KB）
 */
public record EnvironmentConfig(
        EnvironmentType type,
        EnvironmentPackages packages,
        String setupScript
) {

    /** setup_script 字节上限（64 KB） */
    public static final int MAX_SETUP_SCRIPT_BYTES = 64 * 1024;

    /**
     * 紧凑构造器：不变量校验与归一。
     */
    public EnvironmentConfig {
        if (type == null) {
            throw new IllegalArgumentException("环境配置类型不能为空");
        }
        packages = packages == null ? EnvironmentPackages.empty() : packages;
        setupScript = StringUtils.isBlank(setupScript) ? null : setupScript;
        if (type == EnvironmentType.SELF_HOSTED && !packages.isEmpty()) {
            throw new IllegalArgumentException("self_hosted 环境仅支持 type 与可选 setup_script，不支持 packages");
        }
        if (setupScript != null && setupScript.getBytes(StandardCharsets.UTF_8).length > MAX_SETUP_SCRIPT_BYTES) {
            throw new IllegalArgumentException("setup_script 超过 64KB 上限");
        }
    }

    /**
     * 缺省配置：{@code {"type":"cloud"}}（config 缺省时使用）。
     */
    public static EnvironmentConfig cloudDefault() {
        return new EnvironmentConfig(EnvironmentType.CLOUD, EnvironmentPackages.empty(), null);
    }
}
