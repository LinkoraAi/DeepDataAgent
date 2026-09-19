package com.linkroa.deepdataagent.agent.api.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 运行环境引用契约（发布语言 DTO，Published Language）。
 * <p>由 agent BC 在应用边界出版，作为 {@code ResolvedAgentAssemblyDTO} 的环境引用字段，
 * 供下游 runtime BC 的防腐层消费。对外输出已格式化值（环境类型以规范化小写字符串暴露），
 * 不泄露本 BC 领域枚举 {@code EnvironmentType} 与值对象 {@code EnvironmentConfig}。
 * 旧沙箱明细（镜像 / 内存 / CPU / 工作目录模式 / 超时）随 config 结构化改造不再摊平透出，
 * 消费方仅依赖类型与脚本语义做守卫判断。</p>
 *
 * @param environmentId 运行环境业务 ID
 * @param name          运行环境名称
 * @param type          环境类型（规范化小写值：cloud / self_hosted）
 * @param setupScript   环境初始化脚本（可空：null 表示未配置）
 */
public record EnvironmentReferenceDTO(
        String environmentId,
        String name,
        String type,
        String setupScript
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
    }
}
