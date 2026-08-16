package com.linkroa.deepdataagent.agent.application.contract;

import org.apache.commons.lang3.StringUtils;

/**
 * Agent 运行时装配契约（发布语言 DTO，Published Language）。
 * <p>由 agent BC 在应用边界出版，作为 {@code AgentVersionAssemblyPort} 的返回类型，
 * 供下游 runtime BC 的防腐层（ACL）消费，并转换为 runtime 自身领域模型
 * {@code AgentAssemblySpec}。跨 BC 只共享本无逻辑的 DTO，双方领域层互不接触：
 * system ← {@code agent_version.system}、modelIndicator ← api_format + model_name 拼接结果、
 * maxIters ← {@code model_profile.tool_call_rounds}；凭证已在基础设施层解密
 * （不进 {@code AgentAssemblySpec}，直接注入运行时工厂装配配置）。</p>
 *
 * @param agentId        Agent 业务 ID
 * @param versionNumber  发布号（十进制）
 * @param versionName    版本名称（Agent 装配显示名）
 * @param system         系统提示词（可空）
 * @param modelIndicator 模型标识（api_format + model_name 拼接结果，如 openai:gpt-4）
 * @param maxIters       工具调用轮次上限
 * @param credential     解密后的模型凭证（无鉴权时可空）
 * @param apiEndpointUrl 模型 API 端点
 */
public record ResolvedAgentAssemblyDTO(
        String agentId,
        int versionNumber,
        String versionName,
        String system,
        String modelIndicator,
        int maxIters,
        String credential,
        String apiEndpointUrl
) {

    private static final int MAX_SYSTEM_LENGTH = 20000;

    /**
     * 紧凑构造器：契约边界校验
     */
    public ResolvedAgentAssemblyDTO {
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("Agent ID不能为空");
        }
        if (versionNumber < 1) {
            throw new IllegalArgumentException("发布号必须大于0");
        }
        if (StringUtils.isBlank(versionName)) {
            throw new IllegalArgumentException("版本名称不能为空");
        }
        if (StringUtils.isBlank(modelIndicator)) {
            throw new IllegalArgumentException("模型标识不能为空");
        }
        if (maxIters < 1) {
            throw new IllegalArgumentException("工具调用轮次必须为正数");
        }
        if (system != null && system.length() > MAX_SYSTEM_LENGTH) {
            throw new IllegalArgumentException("系统提示词长度不能超过20000个字符");
        }
    }

    /**
     * 脱敏 toString：明文凭证不随日志/异常链输出（保留前 4 位，其余掩码）。
     */
    @Override
    public String toString() {
        return "ResolvedAgentAssemblyDTO[agentId=" + agentId
                + ", versionNumber=" + versionNumber
                + ", versionName=" + versionName
                + ", system=" + system
                + ", modelIndicator=" + modelIndicator
                + ", maxIters=" + maxIters
                + ", credential=" + mask(credential)
                + ", apiEndpointUrl=" + apiEndpointUrl + "]";
    }

    /** 凭证打码：非空且长度大于 4 时保留前 4 位，其余替换为掩码（长度不足以保留时全掩码）。 */
    private static String mask(String credential) {
        if (credential == null || credential.isBlank()) {
            return credential;
        }
        if (credential.length() <= 4) {
            return "****";
        }
        return credential.substring(0, 4) + "****";
    }
}